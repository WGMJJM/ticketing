package com.ticketing.entity;

import java.time.LocalDateTime;

/** Redis 提供未完成预扣；MySQL 仅在消费或过期仲裁事务中登记最终结果。 */
public record PurchaseRequest(long id, long userId, long skuId, int quantity,
        String idempotencyKey, byte[] requestHash, String status, Long orderId, String failureCode,
        LocalDateTime acceptedAt, LocalDateTime deadlineAt, LocalDateTime updatedAt,
        String idempotencyOperation, Long waitlistEntryId) {
    public PurchaseRequest(long id, long userId, long skuId, int quantity, String idempotencyKey,
            byte[] requestHash, String status, Long orderId, String failureCode,
            LocalDateTime acceptedAt, LocalDateTime deadlineAt, LocalDateTime updatedAt) {
        this(id, userId, skuId, quantity, idempotencyKey, requestHash, status, orderId, failureCode,
                acceptedAt, deadlineAt, updatedAt, "CREATE_ORDER", null);
    }
    public boolean terminal() {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "EXPIRED".equals(status);
    }
}
