package com.ticketing.messaging.model;

public enum OutboxStatus {
    PENDING,
    PROCESSING,
    SENT,
    FAILED;
}
