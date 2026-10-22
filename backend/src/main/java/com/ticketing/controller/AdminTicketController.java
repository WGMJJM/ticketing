package com.ticketing.controller;

import com.ticketing.common.ApiResponse;
import com.ticketing.dto.VersionRequest;
import com.ticketing.dto.TicketView;
import com.ticketing.dto.TicketWriteRequest;
import com.ticketing.service.TicketService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminTicketController {
    private final TicketService tickets;
    public AdminTicketController(TicketService tickets) { this.tickets = tickets; }

    @GetMapping("/sessions/{sessionId}/tickets")
    public ApiResponse<List<TicketView>> list(@PathVariable @Positive long sessionId) {
        return ApiResponse.success(tickets.adminList(sessionId));
    }

    @PostMapping("/sessions/{sessionId}/tickets")
    public ApiResponse<TicketView> create(@PathVariable @Positive long sessionId, @Valid @RequestBody TicketWriteRequest request) {
        return ApiResponse.success(tickets.create(sessionId, request));
    }

    @PutMapping("/tickets/{ticketId}")
    public ApiResponse<TicketView> update(@PathVariable @Positive long ticketId, @Valid @RequestBody TicketWriteRequest request) {
        return ApiResponse.success(tickets.update(ticketId, request));
    }

    @PostMapping("/tickets/{ticketId}/publish")
    public ApiResponse<TicketView> publish(@PathVariable @Positive long ticketId, @Valid @RequestBody VersionRequest request) {
        return ApiResponse.success(tickets.publish(ticketId, request));
    }

    @PostMapping("/tickets/{ticketId}/offline")
    public ApiResponse<TicketView> offline(@PathVariable @Positive long ticketId, @Valid @RequestBody VersionRequest request) {
        return ApiResponse.success(tickets.offline(ticketId, request));
    }
}
