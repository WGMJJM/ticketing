package com.ticketing.event.dto;

import com.ticketing.event.entity.ActivitySession;
import com.ticketing.event.model.SessionStatus;
import java.time.Instant;
import java.time.ZoneOffset;

public record SessionView(String id, String activityId, String title, Instant startsAt, Instant endsAt,
                          Instant saleStartsAt, SessionStatus status, int version) {
    public static SessionView from(ActivitySession session) {
        return new SessionView(session.getId().toString(), session.getActivityId().toString(), session.getTitle(),
                session.getStartsAt().toInstant(ZoneOffset.UTC), session.getEndsAt().toInstant(ZoneOffset.UTC),
                session.getSaleStartsAt().toInstant(ZoneOffset.UTC), SessionStatus.valueOf(session.getStatus()),
                session.getVersion());
    }
}
