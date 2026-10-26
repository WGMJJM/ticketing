-- 先停写并排空旧链路；不把旧活动申请猜测为失败。
CREATE TEMPORARY TABLE purchase_refactor_guard (
    pending_count BIGINT NOT NULL CHECK (pending_count = 0)
);
INSERT INTO purchase_refactor_guard SELECT COUNT(*) FROM purchase_request WHERE status IN ('QUEUED','PROCESSING');
DROP TEMPORARY TABLE purchase_refactor_guard;
DELETE FROM outbox_event WHERE aggregate_type='PURCHASE_REQUEST' AND event_type='PURCHASE_REQUEST_ACCEPTED';
DROP TABLE purchase_capacity;
ALTER TABLE purchase_request
    DROP CHECK ck_purchase_lease,
    DROP CHECK ck_purchase_token,
    DROP CHECK ck_purchase_status,
    DROP CHECK ck_purchase_result,
    DROP INDEX idx_purchase_recovery,
    DROP INDEX uk_purchase_stock_reservation,
    DROP COLUMN processing_lease_until,
    DROP COLUMN processing_token,
    DROP COLUMN retry_count,
    DROP COLUMN next_recovery_at,
    DROP COLUMN stock_reservation_token,
    ADD CONSTRAINT ck_purchase_status CHECK (status IN ('QUEUED','SUCCEEDED','FAILED','EXPIRED')),
    ADD CONSTRAINT ck_purchase_result CHECK (
      (status='SUCCEEDED' AND order_id IS NOT NULL AND failure_code IS NULL) OR
      (status IN ('FAILED','EXPIRED') AND order_id IS NULL AND failure_code IS NOT NULL) OR
      (status='QUEUED' AND order_id IS NULL AND failure_code IS NULL));
-- 已初始化的 Redis 库存丢失时停卖，禁止猜测重建丢失的预扣。
ALTER TABLE ticket_sku ADD COLUMN redis_inventory_initialized BOOLEAN NOT NULL DEFAULT FALSE;
