package com.ticketing.controller;

import com.ticketing.common.ApiResponse;
import com.ticketing.common.PageResponse;
import com.ticketing.security.AccountPrincipal;
import com.ticketing.service.TicketNotificationService;
import com.ticketing.vo.TicketNotificationView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/notifications")
public class TicketNotificationController {
    private final TicketNotificationService notifications;

    public TicketNotificationController(TicketNotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public ApiResponse<PageResponse<TicketNotificationView>> list(@AuthenticationPrincipal AccountPrincipal user,
            @RequestParam(defaultValue = "false") boolean unread,
            @RequestParam(defaultValue = "1") @Min(1) @Max(10000) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return ApiResponse.success(notifications.list(user.getId(), unread, page, size));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Long> unreadCount(@AuthenticationPrincipal AccountPrincipal user) {
        return ApiResponse.success(notifications.unreadCount(user.getId()));
    }

    @PutMapping("/{id}/read")
    public ApiResponse<Void> markRead(@AuthenticationPrincipal AccountPrincipal user, @PathVariable @Positive long id) {
        notifications.markRead(user.getId(), id);
        return ApiResponse.success(null);
    }

    @PutMapping("/read")
    public ApiResponse<Void> markAllRead(@AuthenticationPrincipal AccountPrincipal user) {
        notifications.markAllRead(user.getId());
        return ApiResponse.success(null);
    }
}
