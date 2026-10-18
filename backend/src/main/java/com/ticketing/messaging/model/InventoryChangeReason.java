package com.ticketing.messaging.model;

public enum InventoryChangeReason {
    ORDER_CANCELLED,
    ORDER_EXPIRED,
    STOCK_INCREASED,
    WAITLIST_JOINED,
    WAITLIST_CANCELLED;
}
