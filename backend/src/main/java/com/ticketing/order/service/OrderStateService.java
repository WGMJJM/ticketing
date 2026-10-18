package com.ticketing.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ticketing.common.api.BusinessException;
import com.ticketing.event.model.ActivityStatus;
import com.ticketing.event.model.SessionStatus;
import com.ticketing.messaging.InventoryEvents;
import com.ticketing.messaging.model.InventoryChangeReason;
import com.ticketing.order.api.OrderLifecycle;
import com.ticketing.order.api.TradeResourceLocker;
import com.ticketing.order.dto.PaymentView;
import com.ticketing.order.entity.PaymentRecord;
import com.ticketing.order.entity.TicketOrder;
import com.ticketing.order.mapper.OrderMapper;
import com.ticketing.order.mapper.PaymentMapper;
import com.ticketing.order.model.OrderStatus;
import com.ticketing.order.model.PaymentStatus;
import com.ticketing.ticket.api.TicketInventory;
import com.ticketing.ticket.api.TicketSale;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
    private final InventoryEvents events;
    private final List<OrderLifecycle> participants;

    public OrderStateService(
            OrderMapper orders,
            PaymentMapper payments,
            TradeResourceLocker resources,
            TicketInventory inventory,
            InventoryEvents events,
            List<OrderLifecycle> participants) {
        this.orders = orders;
        this.payments = payments;
        this.resources = resources;
        this.inventory = inventory;
        this.events = events;
        this.participants = List.copyOf(participants);
    }

    public void cancel(long userId, long orderId) {
        LockedOrder locked = lock(userId, orderId, true);
        if (locked.status() == OrderStatus.CANCELLED) {
            return;
        }
        requireTransition(locked.status(), OrderStatus.CANCELLED, "只有待支付订单可以主动取消");
        if (orders.markCancelled(orderId, userId) != 1) {
            throw conflict("ORDER_STATE_CONFLICT", "订单状态已变化，请刷新后重试");
        }
        inventory.release(locked.sale().skuId(), locked.order().getQuantity());
        complete(locked.order(), OrderStatus.CANCELLED);
        events.append(locked.sale().skuId(), InventoryChangeReason.ORDER_CANCELLED);
    }

    public PaymentView pay(long userId, long orderId) {
        LockedOrder locked = lock(userId, orderId, true);
        if (locked.status() == OrderStatus.PAID) {
            return payment(userId, orderId);
        }
        requireTransition(locked.status(), OrderStatus.PAID, "订单已取消或已过期，不能支付");
        if (locked.sale().activityStatus() != ActivityStatus.PUBLISHED
                || locked.sale().sessionStatus() != SessionStatus.SCHEDULED) {
            throw conflict("ORDER_UNPAYABLE", "活动已下架或场次已取消、结束，不能继续支付");
        }
        if (orders.markPaid(orderId, userId) != 1) {
            throw conflict("ORDER_EXPIRED", "支付已截止，请刷新查看订单状态");
        }
        inventory.sell(locked.sale().skuId(), locked.order().getQuantity());
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

    public boolean expire(long userId, long orderId) {
        LockedOrder locked = lock(userId, orderId, false);
        if (!locked.status().canTransitionTo(OrderStatus.EXPIRED)
                || orders.markExpired(orderId, userId) != 1) {
            return false;
        }
        inventory.release(locked.sale().skuId(), locked.order().getQuantity());
        complete(locked.order(), OrderStatus.EXPIRED);
        events.append(locked.sale().skuId(), InventoryChangeReason.ORDER_EXPIRED);
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

    private LockedOrder lock(long userId, long orderId, boolean requireActive) {
        resources.lockUser(userId, requireActive);
        TicketOrder order = requireOwned(userId, orderId);
        TicketSale sale = resources.lockSale(order.getSkuId());
        return new LockedOrder(order, sale, resources.lockOrder(orderId, userId));
    }

    private TicketOrder requireOwned(long userId, long orderId) {
        TicketOrder order = orders.owned(orderId, userId);
        if (order == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "订单不存在");
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
