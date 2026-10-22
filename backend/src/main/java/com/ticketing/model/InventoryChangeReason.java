package com.ticketing.model;

public enum InventoryChangeReason {
    ORDER_CANCELLED,
    ORDER_EXPIRED,
    ORDER_REFUNDED,
    STOCK_INCREASED,
    WAITLIST_JOINED,
    WAITLIST_CANCELLED;
}
