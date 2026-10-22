package com.ticketing.order.controller;

import com.ticketing.auth.security.AccountPrincipal;
import com.ticketing.common.api.ApiResponse;
import com.ticketing.common.api.PageResponse;
import com.ticketing.order.dto.AdminOrderDetail;
import com.ticketing.order.dto.AdminOrderQuery;
import com.ticketing.order.dto.AdminOrderView;
import com.ticketing.order.dto.RefundRequest;
import com.ticketing.order.dto.RefundResult;
import com.ticketing.order.service.AdminOrderService;
import com.ticketing.order.service.OrderRefundService;
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
