package com.ticketing.activity;

import com.ticketing.common.ApiResponse;
import com.ticketing.common.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/activities")
public class ActivityController {
    private final ActivityService activities;
    private final SessionService sessions;
    public ActivityController(ActivityService activities, SessionService sessions) {
        this.activities = activities;
        this.sessions = sessions;
    }

    @GetMapping
    public ApiResponse<PageResponse<ActivityView>> list(@Valid @ModelAttribute ActivityQuery query) {
        return ApiResponse.success(activities.list(query, true));
    }

    @GetMapping("/{id}")
    public ApiResponse<ActivityView> detail(@PathVariable @Positive long id) {
        return ApiResponse.success(activities.detail(id, true));
    }

    @GetMapping("/{id}/sessions")
    public ApiResponse<List<SessionView>> sessions(@PathVariable @Positive long id) {
        return ApiResponse.success(sessions.list(id, true));
    }
}
