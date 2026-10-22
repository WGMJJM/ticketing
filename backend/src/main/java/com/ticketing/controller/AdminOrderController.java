package com.ticketing.controller;

import com.ticketing.security.AccountPrincipal;
import com.ticketing.common.ApiResponse;
import com.ticketing.common.PageResponse;
import com.ticketing.dto.AdminOrderDetail;
import com.ticketing.dto.AdminOrderQuery;
import com.ticketing.dto.AdminOrderView;
import com.ticketing.dto.RefundRequest;
import com.ticketing.dto.RefundResult;
import com.ticketing.service.AdminOrderService;
import com.ticketing.service.OrderRefundService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@RequestMapping("/api/v1/admin/orders")
public class AdminOrderController {
    private final AdminOrderService orders;
    private final OrderRefundService refunds;

    public AdminOrderController(AdminOrderService orders, OrderRefundService refunds) {
        this.orders = orders;
        this.refunds = refunds;
    }

    @GetMapping
    public ApiResponse<PageResponse<AdminOrderView>> list(@Valid @ModelAttribute AdminOrderQuery query,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.success(orders.list(query));
    }

    @GetMapping("/{id}")
    public ApiResponse<AdminOrderDetail> detail(@PathVariable @Positive long id, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.success(orders.detail(id));
    }

    @PostMapping("/{id}/refund")
    public ApiResponse<RefundResult> refund(@AuthenticationPrincipal AccountPrincipal user,
            @PathVariable @Positive long id, @Valid @RequestBody RefundRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.success(refunds.refund(user.getId(), id, request.reason()));
    }
}
