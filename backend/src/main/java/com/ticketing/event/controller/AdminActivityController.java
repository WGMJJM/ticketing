package com.ticketing.event.controller;

import com.ticketing.common.api.ApiResponse;
import com.ticketing.common.api.PageResponse;
import com.ticketing.event.dto.ActivityQuery;
import com.ticketing.event.dto.ActivityView;
import com.ticketing.event.dto.ActivityWriteRequest;
import com.ticketing.event.dto.VersionRequest;
import com.ticketing.event.service.ActivityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/activities")
public class AdminActivityController {
    private final ActivityService activities;
    public AdminActivityController(ActivityService activities) { this.activities = activities; }

    @GetMapping
    public ApiResponse<PageResponse<ActivityView>> list(@Valid @ModelAttribute ActivityQuery query) {
        return ApiResponse.success(activities.list(query, false));
    }

    @GetMapping("/{id}")
    public ApiResponse<ActivityView> detail(@PathVariable @Positive long id) {
        return ApiResponse.success(activities.detail(id, false));
    }

    @PostMapping
    public ApiResponse<ActivityView> create(@Valid @RequestBody ActivityWriteRequest request) {
        return ApiResponse.success(activities.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<ActivityView> update(@PathVariable @Positive long id, @Valid @RequestBody ActivityWriteRequest request) {
        return ApiResponse.success(activities.update(id, request));
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<ActivityView> publish(@PathVariable @Positive long id, @Valid @RequestBody VersionRequest request) {
        return ApiResponse.success(activities.publish(id, request.version()));
    }

    @PostMapping("/{id}/offline")
    public ApiResponse<ActivityView> offline(@PathVariable @Positive long id, @Valid @RequestBody VersionRequest request) {
        return ApiResponse.success(activities.offline(id, request.version()));
    }
}
