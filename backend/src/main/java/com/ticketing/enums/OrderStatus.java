package com.ticketing.enums;

public enum OrderStatus {
    PENDING,
    PAID,
    CANCELLED,
    EXPIRED,
    REFUNDED;

    public boolean canTransitionTo(OrderStatus target) {
        return (this == PENDING && (target == PAID || target == CANCELLED || target == EXPIRED))
                || (this == PAID && target == REFUNDED);
    }
}
