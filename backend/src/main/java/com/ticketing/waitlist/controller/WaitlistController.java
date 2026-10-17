package com.ticketing.waitlist.controller;

import com.ticketing.auth.security.AccountPrincipal;
import com.ticketing.common.api.ApiResponse;
import com.ticketing.common.api.PageResponse;
import com.ticketing.waitlist.dto.JoinWaitlistRequest;
import com.ticketing.waitlist.dto.WaitlistView;
import com.ticketing.waitlist.service.WaitlistService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/waitlist")
public class WaitlistController {
    private final WaitlistService service;
    public WaitlistController(WaitlistService service) { this.service=service; }
    @PostMapping
    public ApiResponse<WaitlistView> join(@AuthenticationPrincipal AccountPrincipal user,@Valid @RequestBody JoinWaitlistRequest request) {
        return ApiResponse.success(service.join(user.getId(),request));
    }
    @GetMapping
    public ApiResponse<PageResponse<WaitlistView>> list(@AuthenticationPrincipal AccountPrincipal user,
            @RequestParam(defaultValue="1") @Min(1) @Max(10000) int page,
            @RequestParam(defaultValue="10") @Min(1) @Max(50) int size) {
        return ApiResponse.success(service.list(user.getId(),page,size));
    }
    @GetMapping("/{id}")
    public ApiResponse<WaitlistView> detail(@AuthenticationPrincipal AccountPrincipal user,@PathVariable @Positive long id) {
        return ApiResponse.success(service.detail(user.getId(),id));
    }
    @PostMapping("/{id}/cancel")
    public ApiResponse<WaitlistView> cancel(@AuthenticationPrincipal AccountPrincipal user,@PathVariable @Positive long id) {
        return ApiResponse.success(service.cancel(user.getId(),id));
    }
}
