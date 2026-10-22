package com.ticketing.dto;

public record PurchaseLimit(int maxPerSession, long usedQuantity, long remainingQuantity) {}
