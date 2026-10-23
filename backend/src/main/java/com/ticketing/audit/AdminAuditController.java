package com.ticketing.audit;

import com.ticketing.common.ApiResponse;
import com.ticketing.common.PageResponse;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/audit-logs")
public class AdminAuditController {
    private final AuditWriter service;

    public AdminAuditController(AuditWriter service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResponse<AuditView>> list(@Valid @ModelAttribute AuditQuery query) {
        return ApiResponse.success(service.list(query));
    }
}
