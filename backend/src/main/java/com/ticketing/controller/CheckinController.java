package com.ticketing.controller;

import com.ticketing.common.ApiResponse;
import com.ticketing.common.PageResponse;
import com.ticketing.dto.CheckinRequest;
import com.ticketing.dto.ElectronicTicketView;
import com.ticketing.dto.StaffPermissionView;
import com.ticketing.security.AccountPrincipal;
import com.ticketing.service.ElectronicTicketService;
import com.ticketing.service.StaffService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/checkins")
public class CheckinController {
    private final ElectronicTicketService tickets;
    private final StaffService staff;

    public CheckinController(ElectronicTicketService tickets, StaffService staff) {
        this.tickets = tickets;
        this.staff = staff;
    }

    @PostMapping
    public ApiResponse<ElectronicTicketView> checkIn(
            @AuthenticationPrincipal AccountPrincipal actor,
            @Valid @RequestBody CheckinRequest request) {
        return ApiResponse.success(tickets.checkIn(actor.getId(), request.credential(), true));
    }

    @GetMapping("/sessions")
    public ApiResponse<PageResponse<StaffPermissionView>> sessions(
            @AuthenticationPrincipal AccountPrincipal actor,
            @RequestParam(defaultValue = "1") @Min(1) @Max(10000) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return ApiResponse.success(staff.available(actor.getId(), page, size));
    }
}
