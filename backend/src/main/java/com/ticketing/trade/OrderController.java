package com.ticketing.trade;

import com.ticketing.auth.AccountPrincipal;
import com.ticketing.admission.PurchaseEntryService;
import com.ticketing.admission.PurchaseRequestView;
import com.ticketing.common.ApiResponse;
import com.ticketing.common.BusinessException;
import com.ticketing.common.PageResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
    private final OrderService orders;
    private final PurchaseEntryService admission;

    public OrderController(OrderService orders, PurchaseEntryService admission) {
        this.orders = orders;
        this.admission = admission;
    }

    @PostMapping
    public org.springframework.http.ResponseEntity<ApiResponse<PurchaseRequestView>> create(
            @AuthenticationPrincipal AccountPrincipal user,
            @Valid @RequestBody CreateOrderRequest request,
            @RequestHeader("Idempotency-Key")
                    @Pattern(regexp = "[!-~]{1,128}", message = "请提供1至128位可见ASCII字符的幂等键")
                    String key) {
        var accepted = admission.accept(user.getId(), request.skuId(), request.quantity(), key);
        return org.springframework.http.ResponseEntity.status(accepted.terminal() ? 200 : 202)
                .cacheControl(org.springframework.http.CacheControl.noStore()).body(ApiResponse.success(accepted));
    }

    @GetMapping
    public ApiResponse<PageResponse<OrderView>> list(
            @AuthenticationPrincipal AccountPrincipal user,
            @RequestParam(defaultValue = "1") @Min(1) @Max(10000) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return ApiResponse.success(orders.list(user.getId(), page, size));
    }

    @GetMapping("/limits")
    public ApiResponse<PurchaseLimit> limits(
            @AuthenticationPrincipal AccountPrincipal user,
            @RequestParam @Positive long sessionId) {
        return ApiResponse.success(orders.limits(user.getId(), sessionId));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderView> detail(
            @AuthenticationPrincipal AccountPrincipal user, @PathVariable @Positive long orderId) {
        return ApiResponse.success(orders.detail(user.getId(), orderId));
    }

    @PostMapping("/{orderId}/cancel")
    public ApiResponse<OrderView> cancel(
            @AuthenticationPrincipal AccountPrincipal user, @PathVariable @Positive long orderId) {
        return ApiResponse.success(orders.cancel(user.getId(), orderId));
    }

    @PostMapping("/{orderId}/pay")
    public ApiResponse<PaymentView> pay(
            @AuthenticationPrincipal AccountPrincipal user,
            @PathVariable @Positive long orderId,
            @RequestBody(required = false) Map<String, Object> body) {
        if (body != null && !body.isEmpty())
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "模拟支付不接受客户端指定金额或其他业务字段");
        return ApiResponse.success(orders.pay(user.getId(), orderId));
    }

    @GetMapping("/{orderId}/payment")
    public ApiResponse<PaymentView> payment(
            @AuthenticationPrincipal AccountPrincipal user, @PathVariable @Positive long orderId) {
        return ApiResponse.success(orders.payment(user.getId(), orderId));
    }
}
