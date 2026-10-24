package com.ticketing.admission;

import com.ticketing.auth.AccountPrincipal;
import com.ticketing.common.ApiResponse;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/purchase-requests")
public class PurchaseRequestsController {
    private final PurchaseAdmission admission;
    public PurchaseRequestsController(PurchaseAdmission admission) { this.admission=admission; }
    // 已包含在SecurityConfig共用purchaseRequests匹配器中，授权前复查账号状态。
    @GetMapping("/lookup")
    public ResponseEntity<ApiResponse<PurchaseRequestView>> lookup(@AuthenticationPrincipal AccountPrincipal user,
            @RequestHeader("Idempotency-Key") @Pattern(regexp="[!-~]{1,128}") String key) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(admission.lookup(user.getId(),key)));
    }
    @GetMapping("/{requestId}")
    public ResponseEntity<ApiResponse<PurchaseRequestView>> detail(@AuthenticationPrincipal AccountPrincipal user,@PathVariable @Positive long requestId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(admission.detail(user.getId(),requestId)));
    }
    @GetMapping
    public ResponseEntity<ApiResponse<List<PurchaseRequestView>>> active(@AuthenticationPrincipal AccountPrincipal user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(admission.active(user.getId())));
    }
}
