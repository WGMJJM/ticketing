package com.ticketing.vo;

import java.time.Instant;

public record TicketNotificationView(String id, String kind, String orderId, String activityTitle,
        String sessionTitle, Instant createdAt, Instant readAt, Instant paymentDeadline, boolean paymentAvailable) {}
