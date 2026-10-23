package com.ticketing.trade;

public record PurchaseLimit(int maxPerSession, long usedQuantity, long remainingQuantity) {}
