package com.ticketing.order.dto;

import java.time.Instant;

public record RefundView(String orderId, String refundNo, String paymentNo, String amountCents,
        String operatorId, String reason, String status, Instant refundedAt, boolean simulated) {}
