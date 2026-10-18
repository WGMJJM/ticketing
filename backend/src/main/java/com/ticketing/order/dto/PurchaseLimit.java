package com.ticketing.order.dto;

public record PurchaseLimit(int maxPerSession, long usedQuantity, long remainingQuantity) {}
