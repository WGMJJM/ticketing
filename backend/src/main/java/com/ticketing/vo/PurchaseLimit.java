package com.ticketing.vo;

public record PurchaseLimit(int maxPerSession, long usedQuantity, long remainingQuantity) {}
