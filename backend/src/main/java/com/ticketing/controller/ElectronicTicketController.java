package com.ticketing.controller;

import com.ticketing.common.ApiResponse;
import com.ticketing.common.PageResponse;
import com.ticketing.security.AccountPrincipal;
import com.ticketing.service.ElectronicTicketService;
import com.ticketing.vo.ElectronicTicketView;
import com.ticketing.vo.TicketCredentialView;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tickets")
public class ElectronicTicketController {
    private final ElectronicTicketService service;
    public ElectronicTicketController(ElectronicTicketService service) { this.service = service; }
    @GetMapping
    public ApiResponse<PageResponse<ElectronicTicketView>> list(@AuthenticationPrincipal AccountPrincipal user,
            @RequestParam(required = false) @Positive Long orderId,
            @RequestParam(required = false) @Pattern(regexp = "UNUSED|USED|VOID") String status,
            @RequestParam(defaultValue = "1") @Min(1) @Max(10000) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return ApiResponse.success(service.list(user.getId(), orderId, status, page, size));
    }
    @GetMapping("/{id}")
    public ApiResponse<ElectronicTicketView> detail(@AuthenticationPrincipal AccountPrincipal user, @PathVariable @Positive long id) {
        return ApiResponse.success(service.detail(user.getId(), id));
    }
    @GetMapping("/{id}/credential")
    public ResponseEntity<ApiResponse<TicketCredentialView>> credential(@AuthenticationPrincipal AccountPrincipal user, @PathVariable @Positive long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache")
                .body(ApiResponse.success(service.credential(user.getId(), id)));
    }
}
