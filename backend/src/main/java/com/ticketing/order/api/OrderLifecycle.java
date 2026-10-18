package com.ticketing.order.api;

import com.ticketing.order.model.OrderStatus;

/** 在订单行锁之后同步调用；参与者失败必须回滚整个交易，不得异步或另开事务。 */
public interface OrderLifecycle {
    int WAITLIST_PHASE = 10;
    int TICKET_PHASE = 20;

    void onTransition(Transition transition);

    record Transition(
            long orderId,
            long userId,
            long sessionId,
            long skuId,
            int quantity,
            String operation,
            String idempotencyKey,
            OrderStatus target) {}
}
