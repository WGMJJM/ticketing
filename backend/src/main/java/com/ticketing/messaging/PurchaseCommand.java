package com.ticketing.messaging;

/** 只包含关联标识，业务参数必须从持久请求读取。 */
public record PurchaseCommand(String eventId, String requestId, int version) {
    public static final String TYPE = "PURCHASE_REQUEST_ACCEPTED";
    public static final String AGGREGATE = "PURCHASE_REQUEST";
    public static final String ROUTING_KEY = "purchase.accepted";
    public static String eventId(long requestId) { return "purchase-" + requestId; }
}
