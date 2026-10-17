package com.example.ticketing.event.controller;

import com.example.ticketing.common.api.ApiResponse;
import com.example.ticketing.common.api.PageResponse;
import com.example.ticketing.event.dto.ActivityQuery;
import com.example.ticketing.event.dto.ActivityView;
import com.example.ticketing.event.dto.SessionView;
import com.example.ticketing.event.service.ActivityService;
import com.example.ticketing.event.service.SessionService;
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
