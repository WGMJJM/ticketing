package com.ticketing.staff;

import java.time.OffsetDateTime;

public record StaffPermissionView(
        String staffId,
        String sessionId,
        String activityTitle,
        String sessionTitle,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        String sessionStatus,
        String grantedBy,
        OffsetDateTime grantedAt,
        OffsetDateTime revokedAt) {}
