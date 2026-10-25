package com.ticketing.entity;

import java.time.LocalDateTime;

/** 仅由已提交数据库记录提供交易身份和参数；消息不携带业务快照。 */
public record PurchaseRequest(long id, long userId, long skuId, int quantity,
        String idempotencyKey, byte[] requestHash, String status, Long orderId, String failureCode,
        LocalDateTime acceptedAt, LocalDateTime deadlineAt, LocalDateTime processingLeaseUntil,
        long processingToken, int retryCount, LocalDateTime nextRecoveryAt, LocalDateTime updatedAt) {
    public boolean terminal() {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "EXPIRED".equals(status);
    }
}
