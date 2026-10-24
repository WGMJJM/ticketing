package com.ticketing.admission;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 非事务HTTP编排；查重及速率准入之后才调用原有可靠受理事务。 */
@Service
public class PurchaseEntryService {
    private final PurchaseAdmission admission;
    private final TicketAdmissionLimiter limiter;
    public PurchaseEntryService(PurchaseAdmission admission, TicketAdmissionLimiter limiter) {
        this.admission = admission; this.limiter = limiter;
    }
    @Transactional(propagation = Propagation.NEVER)
    public PurchaseRequestView accept(long userId, String sku, int quantity, String key) {
        long skuId = admission.skuId(sku);
        PurchaseRequestView existing = admission.replay(userId, skuId, quantity, key);
        if (existing != null) return existing;
        limiter.require(skuId);
        return admission.accept(userId, sku, quantity, key);
    }
}
