package com.ticketing.waitlist.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ticketing.common.api.BusinessException;
import com.ticketing.order.api.OrderLifecycle;
import com.ticketing.order.model.OrderOperation;
import com.ticketing.order.model.OrderStatus;
import com.ticketing.waitlist.entity.WaitlistEntry;
import com.ticketing.waitlist.mapper.WaitlistMapper;
import com.ticketing.waitlist.model.WaitlistStatus;

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

    private BusinessException conflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, "WAITLIST_ORDER_CONFLICT", message);
    }
}
