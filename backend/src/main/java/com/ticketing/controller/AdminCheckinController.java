package com.ticketing.controller;

import com.ticketing.common.ApiResponse;
import com.ticketing.dto.CheckinRequest;
import com.ticketing.security.AccountPrincipal;
import com.ticketing.service.ElectronicTicketService;
import com.ticketing.vo.ElectronicTicketView;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/checkins")
public class AdminCheckinController {
    private final ElectronicTicketService service;
    public AdminCheckinController(ElectronicTicketService service) { this.service = service; }
    @PostMapping
    public ApiResponse<ElectronicTicketView> checkIn(@AuthenticationPrincipal AccountPrincipal user, @Valid @RequestBody CheckinRequest request) {
        return ApiResponse.success(service.checkIn(user.getId(), request.credential(), false));
    }
}
