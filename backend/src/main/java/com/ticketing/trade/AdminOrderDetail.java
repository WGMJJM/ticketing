package com.ticketing.trade;

public record AdminOrderDetail(AdminOrderView summary, WaitlistLink waitlist, TicketCounts tickets,
        RefundView refund, AftercareView aftercare) {
    public record WaitlistLink(String id, String status, String reservedOrderId, String skuId, int quantity) {}
    public record TicketCounts(int total, int unused, int used, int voided) {}
}
