package com.ticketing.entity;

import java.time.LocalDateTime;

/** Redis 提供未完成预扣；MySQL 仅在消费或过期仲裁事务中登记最终结果。 */
public record PurchaseRequest(long id, long userId, long skuId, int quantity,
        String idempotencyKey, byte[] requestHash, String status, Long orderId, String failureCode,
        LocalDateTime acceptedAt, LocalDateTime deadlineAt, LocalDateTime updatedAt) {
    public boolean terminal() {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "EXPIRED".equals(status);
    }
}
