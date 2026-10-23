package com.ticketing.messaging;

public enum OutboxStatus {
    PENDING,
    PROCESSING,
    SENT,
    FAILED;
}
