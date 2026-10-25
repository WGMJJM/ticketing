package com.ticketing.vo;

import com.ticketing.entity.TicketOrder;
import com.ticketing.enums.OrderStatus;
import com.ticketing.enums.PaymentStatus;

import java.time.Instant;
import java.time.ZoneOffset;

public record OrderView(
        String id,
        String orderNo,
        String activityId,
        String activityTitle,
        String sessionId,
        String sessionTitle,
        Instant sessionStartsAt,
        String skuId,
        String skuName,
        Integer quantity,
        String unitPriceCents,
        String totalAmountCents,
        String status,
        Instant createdAt,
        Instant expiresAt,
        boolean paymentDeadlinePassed,
        boolean canCancel,
        boolean canPay,
        PaymentView payment,
        RefundView refund,
        Instant serverTime) {
    public static OrderView from(TicketOrder order, Instant now) {
        Instant deadline = order.getExpiresAt().toInstant(ZoneOffset.UTC);
        boolean pending = OrderStatus.PENDING.name().equals(order.getStatus());
        PaymentView payment =
                order.getPaymentNo() == null
                        ? null
                        : new PaymentView(
                                order.getId().toString(),
                                order.getPaymentNo(),
                                order.getPaymentAmountCents().toString(),
                                PaymentStatus.SUCCEEDED.name(),
                                order.getPaymentPaidAt().toInstant(ZoneOffset.UTC),
                                true);
        return new OrderView(
                order.getId().toString(),
                order.getOrderNo(),
                order.getActivityId().toString(),
                order.getActivityTitle(),
                order.getSessionId().toString(),
                order.getSessionTitle(),
                order.getSessionStartsAt().toInstant(ZoneOffset.UTC),
                order.getSkuId() == null ? null : order.getSkuId().toString(),
                order.getSkuNameSnapshot(),
                order.getQuantity(),
                order.getUnitPriceCents() == null ? null : order.getUnitPriceCents().toString(),
                order.getTotalAmountCents().toString(),
                order.getStatus(),
                order.getCreatedAt().toInstant(ZoneOffset.UTC),
                deadline,
                pending && !now.isBefore(deadline),
                pending && order.getSkuId() != null,
                pending
                        && order.getSkuId() != null
                        && now.isBefore(deadline)
                        && "PUBLISHED".equals(order.getActivityStatus())
                        && "SCHEDULED".equals(order.getSessionStatus()),
                payment,
                order.getRefundNo() == null ? null : new RefundView(order.getId().toString(),
                        order.getRefundNo(), order.getPaymentNo(), order.getTotalAmountCents().toString(),
                        null, order.getRefundReason(), "SUCCEEDED", order.getRefundedAt().toInstant(ZoneOffset.UTC), true),
                now);
    }
}
