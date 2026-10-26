package com.ticketing.vo;

import java.util.List;

public record FollowedActivityView(ActivityView activity, List<SessionView> sessions) {
}
