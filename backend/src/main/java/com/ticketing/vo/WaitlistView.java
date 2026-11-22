package com.ticketing.vo;

import java.time.Instant;

public record WaitlistView(String id, String skuId, String sessionId, String activityId, String activityTitle,
        String sessionTitle, String skuName, String priceCents, int quantity, String status,
        Instant createdAt, Instant expiresAt, String reservedOrderId, String orderStatus,
        Instant orderExpiresAt, boolean canCancel) {
    public WaitlistView waiting() {
        return new WaitlistView(id, skuId, sessionId, activityId, activityTitle, sessionTitle,
                skuName, priceCents, quantity, "WAITING", createdAt, expiresAt, reservedOrderId,
                orderStatus, orderExpiresAt, true);
    }
}
