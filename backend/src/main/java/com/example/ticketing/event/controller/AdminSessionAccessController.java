package com.example.ticketing.event.controller;

import com.example.ticketing.common.api.ApiResponse;
import java.security.Principal;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/session")
public class AdminSessionAccessController {
    @GetMapping
    public ResponseEntity<ApiResponse<AdminAccess>> session(Principal principal, CsrfToken csrf) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(new AdminAccess(principal.getName(), csrf.getHeaderName(), csrf.getToken())));
    }

    public record AdminAccess(String username, String csrfHeader, String csrfToken) {
    }
}
