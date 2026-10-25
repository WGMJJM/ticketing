package com.ticketing.messaging;

import com.ticketing.enums.InventoryChangeReason;

/** 保持第六阶段的消息类型、字段、路由和值，兼容已持久化的历史消息。 */
public record InventoryEvent(String eventId, String skuId, InventoryChangeReason reason) {
    public static final String TYPE = "WAITLIST_DISPATCH_REQUESTED";
    public static final String AGGREGATE = "TICKET_SKU";
    public static final String ROUTING_KEY = "inventory.available";
}
