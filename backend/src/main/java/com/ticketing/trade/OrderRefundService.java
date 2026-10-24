package com.ticketing.trade;

import com.ticketing.audit.AuditWriter;
import com.ticketing.inventory.TicketInventory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ticketing.common.BusinessException;
import com.ticketing.activity.SessionStatus;
import com.ticketing.messaging.InventoryEvents;
import com.ticketing.messaging.InventoryChangeReason;
import com.ticketing.audit.AuditAction;
import java.util.Map;
import com.ticketing.inventory.TicketSale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

/** 管理员与场次善后共用整单退款；支付事实、库存、候补历史和票券在一个事务中处理。 */
@Service
public class OrderRefundService {
    private final OrderMapper orders;
    private final PaymentMapper payments;
    private final RefundMapper refunds;
    private final AftercareMapper aftercare;
    private final TradeResourceLocker resources;
    private final TicketInventory inventory;
    private final InventoryEvents events;
    private final OrderStateService states;
    private final List<OrderLifecycle> participants;
    private final AuditWriter audit;
    private final UserSessionQuotaService quotas;

    public OrderRefundService(OrderMapper orders, PaymentMapper payments, RefundMapper refunds,
            AftercareMapper aftercare, TradeResourceLocker resources, TicketInventory inventory,
            InventoryEvents events, OrderStateService states, List<OrderLifecycle> participants, AuditWriter audit, UserSessionQuotaService quotas) {
        this.orders = orders;
        this.payments = payments;
        this.refunds = refunds;
        this.aftercare = aftercare;
        this.resources = resources;
        this.inventory = inventory;
        this.events = events;
        this.states = states;
        this.participants = List.copyOf(participants);
        this.audit = audit;
        this.quotas = quotas;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RefundResult refund(long operatorId, long orderId, RefundReason reason) {
        Locked locked = lock(orderId, operatorId);
        if (reason == RefundReason.SESSION_CANCELLED
                && locked.sessionStatus() != SessionStatus.CANCELLED) {
            throw conflict("场次未取消，请选择正确的售后原因");
        }
        RefundResult result = refundLocked(locked, operatorId, reason);
        audit.record(operatorId, AuditAction.ORDER_REFUND, "ORDER", orderId,
                Map.of("orderStatus", locked.status().name()),
                Map.of("orderStatus", result.refund() == null ? locked.status().name() : OrderStatus.REFUNDED.name(),
                        "result", result.status(), "reason", reason.name()));
        return result;
    }

    /** 每单一个独立事务，禁用买家也必须释放历史库存。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void processCancelledSession(long orderId) {
        Locked locked = lock(orderId, null);
        if (locked.sessionStatus() != SessionStatus.CANCELLED) {
            return;
        }
        if (locked.sale() == null) {
            manual(orderId, new OrderLifecycle.RefundBlock("ORDER_ITEM_MISSING", "订单明细缺失，需要人工核对库存与售后"));
            return;
        }
        if (locked.status() == OrderStatus.PENDING) {
            states.cancelForSession(locked.order(), locked.sale());
            aftercare.record(orderId, "COMPLETED", "SESSION_ORDER_CLOSED", "场次已取消，待支付订单已关闭并释放库存");
        } else if (locked.status() == OrderStatus.PAID) {
            refundLocked(locked, null, RefundReason.SESSION_CANCELLED);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void recordFailure(long orderId, int delaySeconds, int maxAttempts) {
        Locked locked = lock(orderId, null);
        var existing = aftercare.find(orderId);
        if (locked.sessionStatus() == SessionStatus.CANCELLED
                && (locked.status() == OrderStatus.PENDING || locked.status() == OrderStatus.PAID)
                && (existing == null || "RETRY".equals(existing.status()))) {
            aftercare.failure(orderId, delaySeconds, maxAttempts);
        }
    }

    private RefundResult refundLocked(Locked locked, Long operatorId, RefundReason reason) {
        TicketOrder order = locked.order();
        if (locked.status() == OrderStatus.REFUNDED) {
            var original = refunds.find(order.getId());
            if (original == null) {
                throw conflict("退款记录缺失，需要人工核对");
            }
            return new RefundResult("SUCCEEDED", original, aftercare.find(order.getId()));
        }
        if (!locked.status().canTransitionTo(OrderStatus.REFUNDED)) {
            throw conflict("只有已支付订单可以整单模拟退款");
        }
        if (locked.sale() == null) {
            return manual(order.getId(), new OrderLifecycle.RefundBlock("ORDER_ITEM_MISSING", "订单明细缺失，需要人工核对库存与售后"));
        }
        PaymentRecord payment = payments.selectOne(new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getOrderId, order.getId()));
        if (payment == null || !"SUCCEEDED".equals(payment.getStatus())
                || !order.getTotalAmountCents().equals(payment.getAmountCents())
                || !order.getPaidAt().equals(payment.getPaidAt())
                || order.getQuantity() < 1
                || !validAmount(order)) {
            return manual(order.getId(), new OrderLifecycle.RefundBlock(
                    "PAYMENT_SNAPSHOT_INVALID", "支付或订单明细快照异常，需要人工处理"));
        }
        var transition = new OrderLifecycle.Transition(order.getId(), order.getUserId(),
                order.getSessionId(), order.getSkuId(), order.getQuantity(),
                order.getIdempotencyOperation(), order.getIdempotencyKey(), OrderStatus.REFUNDED);
        // 顺序与支付相同：先候补关联，后电子票；全部检查成功才改变业务事实。
        for (OrderLifecycle participant : participants) {
            var block = participant.refundBlocker(transition);
            if (block != null) {
                return manual(order.getId(), block);
            }
        }
        if (orders.markRefunded(order.getId()) != 1) {
            throw conflict("订单状态已变化，请刷新后重试");
        }
        inventory.refund(order.getSkuId(), order.getQuantity());
        quotas.release(order.getUserId(), order.getSessionId(), order.getQuantity());
        for (OrderLifecycle participant : participants) {
            participant.onTransition(transition);
        }
        if (refunds.insert(order.getId(), UUID.randomUUID().toString().replace("-", ""),
                payment, operatorId, reason) != 1) {
            throw conflict("模拟退款记录未完成，事务已回滚");
        }
        events.append(order.getSkuId(), InventoryChangeReason.ORDER_REFUNDED);
        aftercare.record(order.getId(), "COMPLETED", "REFUND_SUCCEEDED", "整单模拟退款已完成，未发生实际资金退回");
        return new RefundResult("SUCCEEDED", refunds.find(order.getId()), aftercare.find(order.getId()));
    }

    private boolean validAmount(TicketOrder order) {
        try {
            return Math.multiplyExact(order.getUnitPriceCents(), order.getQuantity())
                    == order.getTotalAmountCents();
        } catch (ArithmeticException exception) {
            return false;
        }
    }

    private RefundResult manual(long orderId, OrderLifecycle.RefundBlock block) {
        aftercare.record(orderId, "MANUAL_REQUIRED", block.code(), block.message());
        return new RefundResult("MANUAL_REQUIRED", null, aftercare.find(orderId));
    }

    private Locked lock(long orderId, Long operatorId) {
        // 只发现不可变归属；取得用户及父资源锁后重读状态和快照。
        TicketOrder initial = orders.selectById(orderId);
        if (initial == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "订单不存在");
        }
        resources.lockRefundUsers(initial.getUserId(), operatorId);
        TicketOrder detail = orders.snapshot(orderId);
        if (detail == null || detail.getSkuId() == null) {
            SessionStatus session = resources.lockSession(initial.getSessionId());
            OrderStatus status = resources.lockOrder(orderId, initial.getUserId());
            return new Locked(orders.selectById(orderId), null, status, session);
        }
        TicketSale sale = resources.lockSaleForQuota(initial.getUserId(), detail.getSkuId());
        OrderStatus status = resources.lockOrder(orderId, initial.getUserId());
        return new Locked(orders.snapshot(orderId), sale, status, sale.sessionStatus());
    }

    private BusinessException conflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, "REFUND_STATE_CONFLICT", message);
    }

    private record Locked(TicketOrder order, TicketSale sale, OrderStatus status, SessionStatus sessionStatus) {}
}
