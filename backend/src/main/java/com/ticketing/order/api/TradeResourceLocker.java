package com.ticketing.order.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ticketing.auth.entity.UserAccount;
import com.ticketing.auth.mapper.UserAccountMapper;
import com.ticketing.common.api.BusinessException;
import com.ticketing.event.entity.Activity;
import com.ticketing.event.entity.ActivitySession;
import com.ticketing.event.mapper.ActivityMapper;
import com.ticketing.event.mapper.ActivitySessionMapper;
import com.ticketing.order.entity.TicketOrder;
import com.ticketing.order.mapper.OrderMapper;
import com.ticketing.order.model.OrderStatus;
import com.ticketing.event.model.SessionStatus;
import com.ticketing.ticket.api.TicketSale;
import com.ticketing.ticket.entity.TicketSku;
import com.ticketing.ticket.mapper.TicketSkuMapper;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 所有交易入口共用的锁协议，调用方必须已开启READ_COMMITTED事务。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class TradeResourceLocker {
    private final UserAccountMapper users;
    private final ActivityMapper activities;
    private final ActivitySessionMapper sessions;
    private final TicketSkuMapper tickets;
    private final OrderMapper orders;

    public TradeResourceLocker(
            UserAccountMapper users,
            ActivityMapper activities,
            ActivitySessionMapper sessions,
            TicketSkuMapper tickets,
            OrderMapper orders) {
        this.users = users;
        this.activities = activities;
        this.sessions = sessions;
        this.tickets = tickets;
        this.orders = orders;
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
        TicketSku sku =
                tickets.selectOne(
                        new LambdaQueryWrapper<TicketSku>()
                                .eq(TicketSku::getId, skuId)
                                .last("FOR UPDATE"));
        if (activity == null || session == null || sku == null) {
            throw new BusinessException(HttpStatus.CONFLICT, "TICKET_UNAVAILABLE", "票档所属活动或场次不存在");
        }
        return TicketSale.from(activity, session, sku);
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
        UserAccount account =
                users.selectOne(
                        new LambdaQueryWrapper<UserAccount>()
                                .select(
                                        UserAccount::getId,
                                        UserAccount::getStatus,
                                        UserAccount::getRole)
                                .eq(UserAccount::getId, userId)
                                .last("FOR UPDATE"));
        if (account == null || (requireActive && !"ACTIVE".equals(account.getStatus()))) {
            throw new BusinessException(
                    HttpStatus.UNAUTHORIZED, "ACCOUNT_UNAVAILABLE", "账号已停用或不存在，请重新登录");
        }
        return account;
    }
}
