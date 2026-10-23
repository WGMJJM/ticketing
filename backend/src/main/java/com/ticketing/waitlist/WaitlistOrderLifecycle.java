package com.ticketing.waitlist;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ticketing.common.BusinessException;
import com.ticketing.trade.OrderLifecycle;
import com.ticketing.trade.OrderOperation;
import com.ticketing.trade.OrderStatus;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(OrderLifecycle.WAITLIST_PHASE)
public class WaitlistOrderLifecycle implements OrderLifecycle {
    private final WaitlistMapper waitlists;

    public WaitlistOrderLifecycle(WaitlistMapper waitlists) {
        this.waitlists = waitlists;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onTransition(Transition transition) {
        if (transition.target() == OrderStatus.REFUNDED) {
            // 候补已经完成的历史事实保留，不退回队列或误转EXPIRED。
            return;
        }
        WaitlistEntry entry =
                waitlists.selectOne(
                        new LambdaQueryWrapper<WaitlistEntry>()
                                .eq(WaitlistEntry::getReservedOrderId, transition.orderId())
                                .last("FOR UPDATE"));
        boolean reserved = OrderOperation.WAITLIST_CONFIRM.name().equals(transition.operation());
        if (entry == null && !reserved) {
            return;
        }
        if (entry == null
                || !reserved
                || entry.getUserId() != transition.userId()
                || entry.getSkuId() != transition.skuId()
                || entry.getQuantity() != transition.quantity()
                || !WaitlistStatus.ALLOCATED.name().equals(entry.getStatus())
                || !("waitlist-" + entry.getId()).equals(transition.idempotencyKey())) {
            throw conflict("候补与预留订单的用户、场次、票档或数量不一致");
        }
        WaitlistStatus target =
                transition.target() == OrderStatus.PAID
                        ? WaitlistStatus.COMPLETED
                        : WaitlistStatus.EXPIRED;
        if (waitlists.finishAllocated(entry.getId(), target.name()) != 1) {
            throw conflict("候补状态已变化");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public RefundBlock refundBlocker(Transition transition) {
        WaitlistEntry entry = waitlists.selectOne(new LambdaQueryWrapper<WaitlistEntry>()
                .eq(WaitlistEntry::getReservedOrderId, transition.orderId()).last("FOR UPDATE"));
        boolean reserved = OrderOperation.WAITLIST_CONFIRM.name().equals(transition.operation());
        if (entry == null && !reserved) {
            return null;
        }
        if (entry == null || !reserved || entry.getUserId() != transition.userId()
                || entry.getSkuId() != transition.skuId() || entry.getQuantity() != transition.quantity()
                || !WaitlistStatus.COMPLETED.name().equals(entry.getStatus())
                || !("waitlist-" + entry.getId()).equals(transition.idempotencyKey())) {
            return new RefundBlock("WAITLIST_RELATION_INVALID", "候补与已支付订单关联异常，需要人工处理");
        }
        return null;
    }

    private BusinessException conflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, "WAITLIST_ORDER_CONFLICT", message);
    }
}
