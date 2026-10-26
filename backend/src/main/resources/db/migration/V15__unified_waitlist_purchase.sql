-- V14 已建立统一预扣协议；仅扩展来源与 FIFO 尝试，不改历史订单。
ALTER TABLE purchase_request
    DROP CHECK ck_purchase_operation,
    ADD COLUMN waitlist_entry_id BIGINT UNSIGNED NULL,
    ADD CONSTRAINT ck_purchase_operation CHECK (
      (idempotency_operation='CREATE_ORDER' AND waitlist_entry_id IS NULL) OR
      (idempotency_operation='WAITLIST_CONFIRM' AND waitlist_entry_id IS NOT NULL));

ALTER TABLE waitlist_entry
    DROP CHECK ck_waitlist_status,
    DROP INDEX uk_waitlist_active,
    MODIFY COLUMN active_marker TINYINT GENERATED ALWAYS AS
      (CASE WHEN status IN ('WAITING','PROCESSING','ALLOCATED') THEN 1 ELSE NULL END) STORED,
    ADD COLUMN attempt_no INT NOT NULL DEFAULT 0,
    ADD COLUMN purchase_request_id BIGINT UNSIGNED NULL,
    ADD UNIQUE KEY uk_waitlist_active (user_id,sku_id,active_marker),
    ADD UNIQUE KEY uk_waitlist_purchase_request (purchase_request_id),
    ADD CONSTRAINT ck_waitlist_attempt CHECK (attempt_no >= 0 AND (status <> 'PROCESSING' OR purchase_request_id IS NOT NULL)),
    ADD CONSTRAINT ck_waitlist_status CHECK (status IN ('WAITING','PROCESSING','ALLOCATED','COMPLETED','CANCELLED','EXPIRED'));
