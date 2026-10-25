package com.ticketing.vo;

import com.ticketing.entity.PurchaseRequest;

import java.time.Instant;
import java.time.ZoneOffset;

public record PurchaseRequestView(String requestId, String skuId, int quantity, String status,
        String orderId, String failureCode, String failureMessage, Instant acceptedAt,
        Instant deadlineAt, Instant serverTime) {
    public static PurchaseRequestView from(PurchaseRequest request, Instant now) {
        String message = switch (request.failureCode() == null ? "" : request.failureCode()) {
            case "TICKET_UNAVAILABLE", "STOCK_UNAVAILABLE", "TICKET_NOT_FOUND", "SESSION_NOT_FOUND" -> "活动、场次或票档当前不可售，或库存不足";
            case "PURCHASE_LIMIT" -> "购买数量超过票档或本场限购，请查看已有订单";
            case "ACCOUNT_UNAVAILABLE" -> "账号当前不可购票，请重新登录后查看";
            case "ORDER_AMOUNT_INVALID" -> "该票档金额异常，请选择其他票档";
            case "PURCHASE_COMMAND_DELIVERY_EXHAUSTED" -> "购票消息多次投递失败，本次未创建订单，请重新选择购票";
            case "STOCK_RESERVATION_EXPIRED" -> "购票预扣已安全释放，本次未创建订单，请重新提交购票申请";
            case "QUEUE_EXPIRED" -> "排队已超时，本次没有创建订单，可重新选择购票";
            default -> request.failureCode() == null ? null : "本次购票未完成，请刷新查看";
        };
        return new PurchaseRequestView(Long.toString(request.id()), Long.toString(request.skuId()),
                request.quantity(), request.status(), request.orderId() == null ? null : request.orderId().toString(),
                request.failureCode(), message, request.acceptedAt().toInstant(ZoneOffset.UTC),
                request.deadlineAt().toInstant(ZoneOffset.UTC), now);
    }
    public boolean terminal() { return orderId != null || failureCode != null; }
}
