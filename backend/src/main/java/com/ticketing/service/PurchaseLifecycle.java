package com.ticketing.service;

import com.ticketing.entity.PurchaseRequest;

/** 消费事务中的业务参与者：SKU 锁后校验，建单后关联；异常回滚整个交易。 */
public interface PurchaseLifecycle {
    void validate(PurchaseRequest request);
    void allocated(PurchaseRequest result);
    void failed(PurchaseRequest result);
}
