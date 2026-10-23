package com.ticketing.dto;

import java.time.OffsetDateTime;

public record AuditView(
        String id,
        String operatorId,
        String operatorName,
        String action,
        String resourceType,
        String resourceId,
        OffsetDateTime occurredAt,
        String summary,
        String beforeValues,
        String afterValues,
        String requestId) {}
