package com.ticketing.dto;

import java.time.LocalDateTime;

/** 仅由服务端构造；HTTP 不绑定来源、候补 ID 和尝试号。 */
public record PurchaseIntent(long userId, long skuId, int quantity, String operation,
        String key, Long waitlistEntryId, int attemptNo, LocalDateTime expiresAt, Long requestId) {}
