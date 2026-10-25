-- Redis 预扣 token 与持久购票申请使用同一稳定摘要；保留 V1 至 V10 不变。
ALTER TABLE purchase_request
    ADD COLUMN stock_reservation_token BINARY(32) NULL AFTER request_hash;

-- 既有申请使用相同的可信用户、固定操作域、原幂等键及请求摘要生成 token。
UPDATE purchase_request
SET stock_reservation_token = UNHEX(SHA2(CONCAT(
    user_id, CHAR(10), idempotency_operation, CHAR(10), idempotency_key, CHAR(10), LOWER(HEX(request_hash))
), 256));

ALTER TABLE purchase_request
    MODIFY COLUMN stock_reservation_token BINARY(32) NOT NULL,
    ADD UNIQUE KEY uk_purchase_stock_reservation (stock_reservation_token),
    ADD KEY idx_purchase_sku_status (sku_id, status, id);
