package com.ticketing.order.dto;

import com.ticketing.order.entity.TicketOrder;
import java.time.Instant;
import java.time.ZoneOffset;

public record OrderView(String id, String orderNo, String activityId, String activityTitle, String sessionId,
        String sessionTitle, Instant sessionStartsAt, String skuId, String skuName, int quantity,
        String unitPriceCents, String totalAmountCents, String status, Instant createdAt, Instant expiresAt,
        boolean paymentDeadlinePassed, boolean canCancel, Instant serverTime) {
    public static OrderView from(TicketOrder order, Instant now) {
        Instant deadline = order.getExpiresAt().toInstant(ZoneOffset.UTC);
        boolean pending = "PENDING".equals(order.getStatus());
        return new OrderView(order.getId().toString(), order.getOrderNo(), order.getActivityId().toString(),
                order.getActivityTitle(), order.getSessionId().toString(), order.getSessionTitle(),
                order.getSessionStartsAt().toInstant(ZoneOffset.UTC), order.getSkuId().toString(), order.getSkuNameSnapshot(),
                order.getQuantity(), order.getUnitPriceCents().toString(), order.getTotalAmountCents().toString(),
                order.getStatus(), order.getCreatedAt().toInstant(ZoneOffset.UTC), deadline,
                pending && !now.isBefore(deadline), pending, now);
    }
}
