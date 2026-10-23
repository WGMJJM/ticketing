package com.ticketing.admission;

import com.ticketing.auth.AccountPrincipal;
import com.ticketing.common.ApiResponse;
import jakarta.validation.constraints.Positive;
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
    @GetMapping("/{requestId}")
    public ResponseEntity<ApiResponse<PurchaseRequestView>> detail(@AuthenticationPrincipal AccountPrincipal user,@PathVariable @Positive long requestId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(admission.detail(user.getId(),requestId)));
    }
    @GetMapping
    public ResponseEntity<ApiResponse<List<PurchaseRequestView>>> active(@AuthenticationPrincipal AccountPrincipal user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(admission.active(user.getId())));
    }
}
