package com.ticketing.dto;

import java.time.Instant;

public record ElectronicTicketView(String id, String orderId, String orderItemId, String skuId,
        String ownerId, String ownerName, String ticketNo, int sequenceNo, String status,
        String activityId, String activityTitle, String sessionId, String sessionTitle,
        Instant sessionStartsAt, Instant sessionEndsAt, String city, String venue, String skuName,
        Instant usedAt, String checkedBy, Instant createdAt) {}
