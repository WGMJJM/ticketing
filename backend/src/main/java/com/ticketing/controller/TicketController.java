package com.ticketing.controller;

import com.ticketing.common.ApiResponse;
import com.ticketing.dto.TicketView;
import com.ticketing.service.TicketService;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/activities/{activityId}/sessions/{sessionId}/tickets")
public class TicketController {
    private final TicketService tickets;
    public TicketController(TicketService tickets) { this.tickets = tickets; }

    @GetMapping
    public ResponseEntity<ApiResponse<List<TicketView>>> list(@PathVariable @Positive long activityId,
                                                            @PathVariable @Positive long sessionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(tickets.publicList(activityId, sessionId)));
    }
}
