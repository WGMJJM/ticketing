CREATE TABLE purchase_capacity (
    id TINYINT NOT NULL,
    pending_count INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT ck_purchase_capacity CHECK (id = 1 AND pending_count >= 0)
) ENGINE=InnoDB COMMENT='单实例可靠受理的持久预算，终态同事务释放';
INSERT INTO purchase_capacity(id,pending_count) VALUES(1,0);

CREATE TABLE purchase_request (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    sku_id BIGINT UNSIGNED NOT NULL,
    quantity INT NOT NULL,
    idempotency_operation VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CREATE_ORDER',
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash BINARY(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    order_id BIGINT UNSIGNED NULL,
    failure_code VARCHAR(64) NULL,
    accepted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deadline_at DATETIME(6) NOT NULL,
    processing_lease_until DATETIME(6) NULL,
    processing_token BIGINT NOT NULL DEFAULT 0,
    retry_count INT NOT NULL DEFAULT 0,
    next_recovery_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(id),
    UNIQUE KEY uk_purchase_idempotency(user_id,idempotency_operation,idempotency_key),
    UNIQUE KEY uk_purchase_order(order_id),
    KEY idx_purchase_expiry(status,deadline_at,id),
    KEY idx_purchase_recovery(status,next_recovery_at,id),
    KEY idx_purchase_owner(user_id,id),
    CONSTRAINT fk_purchase_user FOREIGN KEY(user_id) REFERENCES user_account(id),
    CONSTRAINT fk_purchase_order_owner FOREIGN KEY(order_id,user_id) REFERENCES ticket_order(id,user_id),
    CONSTRAINT ck_purchase_quantity CHECK(quantity > 0),
    CONSTRAINT ck_purchase_operation CHECK(idempotency_operation='CREATE_ORDER'),
    CONSTRAINT ck_purchase_deadline CHECK(deadline_at > accepted_at),
    CONSTRAINT ck_purchase_token CHECK(processing_token >= 0 AND retry_count >= 0),
    CONSTRAINT ck_purchase_status CHECK(status IN('QUEUED','PROCESSING','SUCCEEDED','FAILED','EXPIRED')),
    CONSTRAINT ck_purchase_result CHECK(
      (status='SUCCEEDED' AND order_id IS NOT NULL AND failure_code IS NULL) OR
      (status IN('FAILED','EXPIRED') AND order_id IS NULL AND failure_code IS NOT NULL) OR
      (status IN('QUEUED','PROCESSING') AND order_id IS NULL AND failure_code IS NULL)),
    CONSTRAINT ck_purchase_lease CHECK((status='PROCESSING' AND processing_lease_until IS NOT NULL)
      OR (status<>'PROCESSING' AND processing_lease_until IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='购票受理事实，独立于正式订单状态';

-- 只登记旧普通订单的既有幂等事实，不创建订单、不改变订单状态或支付截止时间。
INSERT INTO purchase_request(user_id,sku_id,quantity,idempotency_key,request_hash,status,order_id,accepted_at,deadline_at,updated_at)
SELECT o.user_id,i.sku_id,i.quantity,o.idempotency_key,o.request_hash,'SUCCEEDED',o.id,o.created_at,
       TIMESTAMPADD(SECOND,1,o.created_at),o.updated_at
FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id
WHERE o.idempotency_operation='CREATE_ORDER';

ALTER TABLE outbox_event ADD KEY idx_outbox_channel(event_type,status,next_retry_at,id);
