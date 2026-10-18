package com.ticketing.order.model;

public enum OrderStatus {
    PENDING,
    PAID,
    CANCELLED,
    EXPIRED;

    public boolean canTransitionTo(OrderStatus target) {
        return this == PENDING && (target == PAID || target == CANCELLED || target == EXPIRED);
    }
}
