package com.ticketing.service;

import com.ticketing.vo.PurchaseRequestView;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 非事务HTTP编排；同键回放先读数据库，新的请求再进入Redis真实库存预扣。 */
@Service
public class PurchaseEntryService {
    private final PurchaseAdmission admission;
    public PurchaseEntryService(PurchaseAdmission admission) {
        this.admission = admission;
    }
    @Transactional(propagation = Propagation.NEVER)
    public PurchaseRequestView accept(long userId, String sku, int quantity, String key) {
        return admission.accept(userId, sku, quantity, key);
    }
}
