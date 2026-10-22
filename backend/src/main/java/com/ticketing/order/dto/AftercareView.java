package com.ticketing.order.dto;

import java.time.Instant;

public record AftercareView(String status, String reasonCode, String message, int attemptCount,
        Instant lastAttemptAt, Instant nextRetryAt) {}
