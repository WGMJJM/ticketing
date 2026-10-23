package com.ticketing.waitlist;

import java.time.Instant;

public record WaitlistView(String id, String skuId, String sessionId, String activityId, String activityTitle,
        String sessionTitle, String skuName, String priceCents, int quantity, String status,
        Instant createdAt, Instant expiresAt, String reservedOrderId, String orderStatus,
        Instant orderExpiresAt, boolean canCancel) {}
