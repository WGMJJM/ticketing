package com.ticketing.checkin.controller;

import com.ticketing.auth.security.AccountPrincipal;
import com.ticketing.checkin.dto.CheckinRequest;
import com.ticketing.checkin.dto.ElectronicTicketView;
import com.ticketing.checkin.service.ElectronicTicketService;
import com.ticketing.common.api.ApiResponse;
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
        return ApiResponse.success(service.checkIn(user.getId(), request.credential()));
    }
}
