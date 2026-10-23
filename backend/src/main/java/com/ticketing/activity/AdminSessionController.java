package com.ticketing.activity;

import com.ticketing.common.ApiResponse;
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
public class AdminSessionController {
    private final SessionService sessions;
    public AdminSessionController(SessionService sessions) { this.sessions = sessions; }

    @GetMapping("/activities/{id}/sessions")
    public ApiResponse<List<SessionView>> list(@PathVariable @Positive long id) {
        return ApiResponse.success(sessions.list(id, false));
    }

    @PostMapping("/activities/{id}/sessions")
    public ApiResponse<SessionView> create(@PathVariable @Positive long id, @Valid @RequestBody SessionWriteRequest request) {
        return ApiResponse.success(sessions.create(id, request));
    }

    @PutMapping("/sessions/{id}")
    public ApiResponse<SessionView> update(@PathVariable @Positive long id, @Valid @RequestBody SessionWriteRequest request) {
        return ApiResponse.success(sessions.update(id, request));
    }

    @PostMapping("/sessions/{id}/cancel")
    public ApiResponse<SessionView> cancel(@PathVariable @Positive long id, @Valid @RequestBody VersionRequest request) {
        return ApiResponse.success(sessions.cancel(id, request.version()));
    }
}
