package com.ticketing.service;

import com.ticketing.dto.TicketSale;
import com.ticketing.entity.PaymentRecord;
import com.ticketing.entity.TicketOrder;
import com.ticketing.enums.ActivityStatus;
import com.ticketing.enums.OrderStatus;
import com.ticketing.enums.PaymentStatus;
import com.ticketing.enums.SessionStatus;
import com.ticketing.exception.BusinessException;
import com.ticketing.mapper.OrderMapper;
import com.ticketing.mapper.PaymentMapper;
import com.ticketing.vo.PaymentView;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.util.List;
import java.util.UUID;

/** 调用方开启交易事务；本服务只编排PENDING到终态的原子变化及同步末端协作。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class OrderStateService {
    private final OrderMapper orders;
    private final PaymentMapper payments;
    private final TradeResourceLocker resources;
    private final TicketInventory inventory;
    private final InventoryVersionService versions;
    private final List<OrderLifecycle> participants;
    private final UserSessionQuotaService quotas;

    public OrderStateService(
            OrderMapper orders,
            PaymentMapper payments,
            TradeResourceLocker resources,
            TicketInventory inventory,
            InventoryVersionService versions,
            List<OrderLifecycle> participants, UserSessionQuotaService quotas) {
        this.orders = orders;
        this.payments = payments;
        this.resources = resources;
        this.inventory = inventory;
        this.versions = versions;
        this.participants = List.copyOf(participants);
        this.quotas = quotas;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void cancel(long userId, long orderId) {
        LockedOrder locked = lock(userId, orderId, true, true);
        if (locked.status() == OrderStatus.CANCELLED) {
            return;
        }
        requireTransition(locked.status(), OrderStatus.CANCELLED, "只有待支付订单可以主动取消");
        if (orders.markCancelled(orderId, userId) != 1) {
            throw conflict("ORDER_STATE_CONFLICT", "订单状态已变化，请刷新后重试");
        }
        inventory.release(locked.sale(), locked.order().getQuantity(), orderId);
        quotas.release(userId, locked.sale().sessionId(), locked.order().getQuantity());
        complete(locked.order(), OrderStatus.CANCELLED);
        versions.changed(locked.sale().skuId());
    }

    public PaymentView pay(long userId, long orderId) {
        LockedOrder locked = lock(userId, orderId, true, false);
        if (locked.status() == OrderStatus.PAID) {
            return payment(userId, orderId);
        }
        requireTransition(locked.status(), OrderStatus.PAID, "订单当前状态不允许支付，请刷新查看最新状态");
        if (locked.sale().activityStatus() != ActivityStatus.PUBLISHED
                || locked.sale().sessionStatus() != SessionStatus.SCHEDULED) {
            throw conflict("ORDER_UNPAYABLE", "活动已下架或场次已取消、结束，不能继续支付");
        }
        if (orders.markPaid(orderId, userId) != 1) {
            throw conflict("ORDER_EXPIRED", "支付已截止，请刷新查看订单状态");
        }
        inventory.sell(locked.sale(), locked.order().getQuantity(), orderId);
        TicketOrder paid = orders.selectById(orderId);
        PaymentRecord payment = new PaymentRecord();
        payment.setOrderId(orderId);
        payment.setPaymentNo(UUID.randomUUID().toString().replace("-", ""));
        payment.setAmountCents(locked.order().getTotalAmountCents());
        payment.setStatus(PaymentStatus.SUCCEEDED.name());
        payment.setPaidAt(paid.getPaidAt());
        payment.setCreatedAt(paid.getPaidAt());
        payment.setUpdatedAt(paid.getPaidAt());
        payments.insert(payment);
        complete(locked.order(), OrderStatus.PAID);
        return PaymentView.from(payment);
    }

    /** 系统善后入口已持全部交易资源锁；不因买家停用而遗留预占。 */
    public void cancelForSession(TicketOrder order, TicketSale sale) {
        if (sale.sessionStatus() != SessionStatus.CANCELLED
                || orders.markCancelled(order.getId(), order.getUserId()) != 1) {
            throw conflict("ORDER_STATE_CONFLICT", "场次或订单状态已变化");
        }
        inventory.release(sale, order.getQuantity(), order.getId());
        quotas.release(order.getUserId(), sale.sessionId(), order.getQuantity());
        complete(order, OrderStatus.CANCELLED);
        versions.changed(sale.skuId());
    }

    public boolean expire(long userId, long orderId) {
        LockedOrder locked = lock(userId, orderId, false, true);
        if (!locked.status().canTransitionTo(OrderStatus.EXPIRED)
                || orders.markExpired(orderId, userId) != 1) {
            return false;
        }
        inventory.release(locked.sale(), locked.order().getQuantity(), orderId);
        quotas.release(userId, locked.sale().sessionId(), locked.order().getQuantity());
        complete(locked.order(), OrderStatus.EXPIRED);
        versions.changed(locked.sale().skuId());
        return true;
    }

    public PaymentView payment(long userId, long orderId) {
        requireOwned(userId, orderId);
        PaymentRecord payment =
                payments.selectOne(
                        new LambdaQueryWrapper<PaymentRecord>()
                                .eq(PaymentRecord::getOrderId, orderId)
                                .eq(PaymentRecord::getStatus, PaymentStatus.SUCCEEDED.name()));
        if (payment == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "订单暂无成功的模拟支付记录");
        }
        return PaymentView.from(payment);
    }

    private LockedOrder lock(long userId, long orderId, boolean requireActive, boolean releasesQuota) {
        resources.lockUser(userId, requireActive);
        TicketOrder order = requireOwned(userId, orderId);
        TicketSale sale = releasesQuota ? resources.lockSaleForQuota(userId, order.getSkuId()) : resources.lockSale(order.getSkuId());
        if ("PENDING".equals(order.getStatus()) || "PAID".equals(order.getStatus())) {
            inventory.lockAllocation(sale, orderId, order.getQuantity());
        }
        return new LockedOrder(order, sale, resources.lockOrder(orderId, userId));
    }

    private TicketOrder requireOwned(long userId, long orderId) {
        TicketOrder order = orders.ownedSnapshot(orderId, userId);
        if (order == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "订单不存在");
        }
        if (order.getSkuId() == null || order.getQuantity() == null) {
            throw conflict("ORDER_ITEM_INVALID", "订单明细异常，请联系运营人员核对");
        }
        return order;
    }

    private void complete(TicketOrder order, OrderStatus target) {
        var transition =
                new OrderLifecycle.Transition(
                        order.getId(),
                        order.getUserId(),
                        order.getSessionId(),
                        order.getSkuId(),
                        order.getQuantity(),
                        order.getIdempotencyOperation(),
                        order.getIdempotencyKey(),
                        target);
        // Spring按@Order先候补后电子票；异常直接向外传播，始终属于当前数据库事务。
        for (OrderLifecycle participant : participants) {
            participant.onTransition(transition);
        }
    }

    private void requireTransition(OrderStatus current, OrderStatus target, String message) {
        if (!current.canTransitionTo(target)) {
            throw conflict("ORDER_STATE_CONFLICT", message);
        }
    }

    private BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }

    private record LockedOrder(TicketOrder order, TicketSale sale, OrderStatus status) {}
}
