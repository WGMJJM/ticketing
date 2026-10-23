package com.ticketing.trade;

import java.time.Instant;
import java.time.ZoneOffset;

public record PaymentView(String orderId, String paymentNo, String amountCents, String status,
        Instant paidAt, boolean simulated) {
    public static PaymentView from(PaymentRecord payment) {
        return new PaymentView(payment.getOrderId().toString(), payment.getPaymentNo(),
                payment.getAmountCents().toString(), payment.getStatus(),
                payment.getPaidAt().toInstant(ZoneOffset.UTC), true);
    }
}
