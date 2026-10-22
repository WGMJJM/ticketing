package com.ticketing.model;

public enum OutboxStatus {
    PENDING,
    PROCESSING,
    SENT,
    FAILED;
}
