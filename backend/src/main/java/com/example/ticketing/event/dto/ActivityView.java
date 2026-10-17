package com.example.ticketing.event.dto;

import com.example.ticketing.event.entity.Activity;
import com.example.ticketing.event.model.ActivityCategory;
import com.example.ticketing.event.model.ActivityStatus;
import java.time.Instant;
import java.time.ZoneOffset;

public record ActivityView(String id, String title, ActivityCategory category, String city, String venue,
                           String summary, String description, String coverUrl, ActivityStatus status,
                           Instant publishedAt, Instant createdAt, Instant updatedAt, int version) {
    public static ActivityView from(Activity activity) {
        return new ActivityView(activity.getId().toString(), activity.getTitle(),
                ActivityCategory.valueOf(activity.getCategory()), activity.getCity(), activity.getVenue(),
                activity.getSummary(), activity.getDescription(), activity.getCoverUrl(),
                ActivityStatus.valueOf(activity.getStatus()),
                activity.getPublishedAt() == null ? null : activity.getPublishedAt().toInstant(ZoneOffset.UTC),
                activity.getCreatedAt().toInstant(ZoneOffset.UTC), activity.getUpdatedAt().toInstant(ZoneOffset.UTC),
                activity.getVersion());
    }
}
