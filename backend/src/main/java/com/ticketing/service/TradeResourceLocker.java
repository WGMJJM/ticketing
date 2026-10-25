package com.ticketing.service;

import com.ticketing.dto.TicketSale;
import com.ticketing.dto.TradeContext;
import com.ticketing.entity.Activity;
import com.ticketing.entity.ActivitySession;
import com.ticketing.entity.TicketOrder;
import com.ticketing.entity.TicketSku;
import com.ticketing.entity.UserAccount;
import com.ticketing.enums.OrderStatus;
import com.ticketing.enums.SessionStatus;
import com.ticketing.exception.BusinessException;
import com.ticketing.mapper.ActivityMapper;
import com.ticketing.mapper.ActivitySessionMapper;
import com.ticketing.mapper.OrderMapper;
import com.ticketing.mapper.TicketSkuMapper;
import com.ticketing.mapper.UserAccountMapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;

/** 所有交易入口共用的锁协议，调用方必须已开启READ_COMMITTED事务。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class TradeResourceLocker {
    private final UserAccountMapper users;
    private final ActivityMapper activities;
    private final ActivitySessionMapper sessions;
    private final TicketSkuMapper tickets;
    private final OrderMapper orders;
    private final UserSessionQuotaService quotas;
    private final MeterRegistry meters;
    private final TicketInventory inventory;

    public TradeResourceLocker(
            UserAccountMapper users,
            ActivityMapper activities,
            ActivitySessionMapper sessions,
            TicketSkuMapper tickets,
            OrderMapper orders, UserSessionQuotaService quotas, MeterRegistry meters, TicketInventory inventory) {
        this.users = users;
        this.activities = activities;
        this.sessions = sessions;
        this.tickets = tickets;
        this.orders = orders;
        this.quotas = quotas;
        this.meters = meters;
        this.inventory = inventory;
    }

    public void lockUser(long userId, boolean requireActive) {
        account(userId, requireActive);
    }

    /** 退款同时涉及管理员和买家；所有用户行按ID递增获取，随后才锁父资源。 */
    public void lockRefundUsers(long ownerId, Long operatorId) {
        UserAccount operator = null;
        if (operatorId != null && operatorId < ownerId) {
            operator = account(operatorId, true);
        }
        UserAccount owner = account(ownerId, false);
        if (operatorId != null) {
            operator = operatorId == ownerId ? owner
                    : operator == null ? account(operatorId, true) : operator;
            if (!"ACTIVE".equals(operator.getStatus()) || !"ADMIN".equals(operator.getRole())) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "当前账号没有订单售后权限");
            }
        }
    }

    public TradeContext lockPurchase(long userId, long skuId, boolean requireActive) {
        UserAccount account = account(userId, requireActive);
        return new TradeContext(
                userId,
                "ACTIVE".equals(account.getStatus()),
                "ADMIN".equals(account.getRole()),
                lockSale(skuId));
    }

    /** 真正预占才锁精确额度；候补入队、核销、补票不创建或锁定额度行。 */
    public TradeContext lockReservation(long userId, long skuId, boolean requireActive) {
        UserAccount account = account(userId, requireActive);
        return new TradeContext(userId, "ACTIVE".equals(account.getStatus()), "ADMIN".equals(account.getRole()),
                lockSaleForQuota(userId, skuId));
    }

    public TradeContext lockWaitlistPurchase(long userId, long skuId, boolean requireActive) {
        return waitlist(lockPurchase(userId, skuId, requireActive));
    }

    public TradeContext lockWaitlistReservation(long userId, long skuId, boolean requireActive) {
        return waitlist(lockReservation(userId, skuId, requireActive));
    }

    private TradeContext waitlist(TradeContext context) {
        return new TradeContext(context.userId(), context.active(), context.admin(), inventory.lockWaitlist(context.sale()));
    }

    public TicketSale lockSaleForQuota(long userId, long skuId) {
        return sale(skuId, userId);
    }

    /** 仅供缺失订单明细的异常善后：不存在可锁SKU，仍先锁活动/场次再锁订单并记录人工处理。 */
    public SessionStatus lockSession(long sessionId) {
        ActivitySession initial = sessions.selectById(sessionId);
        if (initial == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "场次不存在");
        }
        activities.selectOne(new LambdaQueryWrapper<Activity>()
                .eq(Activity::getId, initial.getActivityId()).last("FOR SHARE"));
        ActivitySession session = sessions.selectOne(new LambdaQueryWrapper<ActivitySession>()
                .eq(ActivitySession::getId, sessionId).last("FOR SHARE"));
        return SessionStatus.valueOf(session.getStatus());
    }

    /** 已持用户锁后调用；最初两次非锁定读取只发现父ID，之后严格由父到子加锁。 */
    public TicketSale lockSale(long skuId) {
        return sale(skuId, null);
    }

    /** 用户SHARE→活动SHARE→场次SHARE→可选额度UPDATE→票档UPDATE→订单，禁止后置补锁额度。 */
    private TicketSale sale(long skuId, Long quotaUser) {
        TicketSku initial = tickets.selectById(skuId);
        if (initial == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND", "票档不存在");
        }
        ActivitySession parent = sessions.selectById(initial.getSessionId());
        if (parent == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "场次不存在");
        }
        Activity activity =
                activities.selectOne(
                        new LambdaQueryWrapper<Activity>()
                                .eq(Activity::getId, parent.getActivityId())
                                .last("FOR SHARE"));
        ActivitySession session =
                sessions.selectOne(
                        new LambdaQueryWrapper<ActivitySession>()
                                .eq(ActivitySession::getId, parent.getId())
                                .last("FOR SHARE"));
        if (activity == null || session == null) {
            throw new BusinessException(HttpStatus.CONFLICT, "TICKET_UNAVAILABLE", "票档所属活动或场次不存在");
        }
        if (quotaUser != null) quotas.lock(quotaUser, session.getId());
        // 父场次锁稳定策略切换；选择锁类型前重新读取元信息，避免等待管理事务后的陈旧策略。
        TicketSku policy = tickets.selectById(skuId);
        long started = System.nanoTime();
        TicketSku sku;
        try { sku =
                tickets.selectOne(
                        new LambdaQueryWrapper<TicketSku>()
                                .eq(TicketSku::getId, skuId)
                                .last(policy.bucketed() ? "FOR SHARE" : "FOR UPDATE"));
        } finally { meters.timer("ticketing.trade.lock.duration","resource","stock").record(System.nanoTime()-started,TimeUnit.NANOSECONDS); }
        if (sku == null || !sku.getSessionId().equals(session.getId()) || !session.getActivityId().equals(activity.getId())) {
            throw new BusinessException(HttpStatus.CONFLICT, "TICKET_UNAVAILABLE", "票档所属活动或场次不存在");
        }
        return TicketSale.from(activity, session, inventory.balance(sku));
    }

    /** 必须在SKU锁之后调用；核销使用持票人归属，最前面的用户锁仍属于工作人员。 */
    public OrderStatus lockOrder(long orderId, long ownerId) {
        TicketOrder order =
                orders.selectOne(
                        new LambdaQueryWrapper<TicketOrder>()
                                .eq(TicketOrder::getId, orderId)
                                .eq(TicketOrder::getUserId, ownerId)
                                .last("FOR UPDATE"));
        if (order == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "订单不存在");
        }
        return OrderStatus.valueOf(order.getStatus());
    }

    private UserAccount account(long userId, boolean requireActive) {
        long started = System.nanoTime();
        UserAccount account;
        try { account =
                users.selectOne(
                        new LambdaQueryWrapper<UserAccount>()
                                .select(
                                        UserAccount::getId,
                                        UserAccount::getStatus,
                                        UserAccount::getRole)
                                .eq(UserAccount::getId, userId)
                                .last("FOR SHARE"));
        } finally { meters.timer("ticketing.trade.lock.duration","resource","account").record(System.nanoTime()-started,TimeUnit.NANOSECONDS); }
        if (account == null || (requireActive && !"ACTIVE".equals(account.getStatus()))) {
            throw new BusinessException(
                    HttpStatus.UNAUTHORIZED, "ACCOUNT_UNAVAILABLE", "账号已停用或不存在，请重新登录");
        }
        return account;
    }
}
