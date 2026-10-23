package com.ticketing.controller;

import com.ticketing.common.ApiResponse;
import com.ticketing.common.PageResponse;
import com.ticketing.dto.StaffAccountView;
import com.ticketing.dto.StaffPermissionView;
import com.ticketing.dto.StaffQuery;
import com.ticketing.security.AccountPrincipal;
import com.ticketing.service.StaffService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/staff")
public class AdminStaffController {
    private final StaffService service;

    public AdminStaffController(StaffService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResponse<StaffAccountView>> list(
            @Valid @ModelAttribute StaffQuery query) {
        return ApiResponse.success(service.list(query));
    }

    @PostMapping("/{id}/promote")
    public ApiResponse<StaffAccountView> promote(
            @AuthenticationPrincipal AccountPrincipal actor, @PathVariable @Positive long id) {
        return ApiResponse.success(service.role(actor.getId(), id, true));
    }

    @PostMapping("/{id}/demote")
    public ApiResponse<StaffAccountView> demote(
            @AuthenticationPrincipal AccountPrincipal actor, @PathVariable @Positive long id) {
        return ApiResponse.success(service.role(actor.getId(), id, false));
    }

    @PostMapping("/{id}/disable")
    public ApiResponse<StaffAccountView> disable(
            @AuthenticationPrincipal AccountPrincipal actor, @PathVariable @Positive long id) {
        return ApiResponse.success(service.disable(actor.getId(), id));
    }

    @GetMapping("/{id}/sessions")
    public ApiResponse<PageResponse<StaffPermissionView>> permissions(
            @PathVariable @Positive long id,
            @RequestParam(defaultValue = "1") @Min(1) @Max(10000) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return ApiResponse.success(service.permissions(id, page, size));
    }

    @PostMapping("/{id}/sessions/{sessionId}/grant")
    public ApiResponse<StaffPermissionView> grant(
            @AuthenticationPrincipal AccountPrincipal actor,
            @PathVariable @Positive long id,
            @PathVariable @Positive long sessionId) {
        return ApiResponse.success(service.grant(actor.getId(), id, sessionId));
    }

    @PostMapping("/{id}/sessions/{sessionId}/revoke")
    public ApiResponse<StaffPermissionView> revoke(
            @AuthenticationPrincipal AccountPrincipal actor,
            @PathVariable @Positive long id,
            @PathVariable @Positive long sessionId) {
        return ApiResponse.success(service.revoke(actor.getId(), id, sessionId));
    }
}
