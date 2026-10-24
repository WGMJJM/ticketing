-- 仅增加兼容结构；历史及新建票档默认SINGLE，禁止启动时自动转移库存。
ALTER TABLE ticket_sku
    ADD COLUMN inventory_strategy VARCHAR(16) NOT NULL DEFAULT 'SINGLE',
    ADD COLUMN bucket_count INT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_sku_inventory_strategy CHECK (
        (inventory_strategy='SINGLE' AND bucket_count=0)
        OR (inventory_strategy='BUCKETED' AND bucket_count BETWEEN 1 AND 16
            AND locked_stock=0 AND sold_stock=0));

CREATE TABLE ticket_stock_bucket (
    sku_id BIGINT UNSIGNED NOT NULL,
    bucket_no INT NOT NULL,
    total_stock INT NOT NULL,
    locked_stock INT NOT NULL DEFAULT 0,
    sold_stock INT NOT NULL DEFAULT 0,
    PRIMARY KEY (sku_id,bucket_no),
    CONSTRAINT fk_bucket_sku FOREIGN KEY (sku_id) REFERENCES ticket_sku(id),
    CONSTRAINT ck_bucket_number CHECK (bucket_no BETWEEN 0 AND 15),
    CONSTRAINT ck_bucket_stock CHECK (total_stock>=0 AND locked_stock>=0 AND sold_stock>=0
        AND CAST(locked_stock AS SIGNED)+sold_stock<=total_stock)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分桶模式唯一库存写账本';

CREATE TABLE order_stock_allocation (
    order_id BIGINT UNSIGNED NOT NULL,
    sku_id BIGINT UNSIGNED NOT NULL,
    bucket_no INT NOT NULL,
    quantity INT NOT NULL,
    PRIMARY KEY (order_id,sku_id,bucket_no),
    KEY idx_allocation_bucket (sku_id,bucket_no),
    CONSTRAINT fk_allocation_item FOREIGN KEY (order_id,sku_id) REFERENCES ticket_order_item(order_id,sku_id),
    CONSTRAINT fk_allocation_bucket FOREIGN KEY (sku_id,bucket_no) REFERENCES ticket_stock_bucket(sku_id,bucket_no),
    CONSTRAINT ck_allocation_quantity CHECK (quantity>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不可变订单库存归属，终态后保留';

-- 普通购票不写此行；仅在分桶票档上协调候补ID生成及持久队首分配。
CREATE TABLE ticket_waitlist_gate (
    sku_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (sku_id),
    CONSTRAINT fk_waitlist_gate_sku FOREIGN KEY (sku_id) REFERENCES ticket_sku(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE stock_strategy_switch (
    sku_id BIGINT UNSIGNED NOT NULL,
    bucket_count INT NOT NULL,
    operator_id BIGINT UNSIGNED NOT NULL,
    backup_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    previous_locked INT NOT NULL,
    previous_sold INT NOT NULL,
    switched_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (sku_id),
    CONSTRAINT fk_switch_sku FOREIGN KEY (sku_id) REFERENCES ticket_sku(id),
    CONSTRAINT fk_switch_operator FOREIGN KEY (operator_id) REFERENCES user_account(id),
    CONSTRAINT ck_switch_buckets CHECK (bucket_count BETWEEN 1 AND 16)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='离线策略切换与备份审计凭据，不由交易修改';

CREATE VIEW ticket_stock_balance AS
SELECT t.id,t.session_id,t.name,t.price_cents,t.total_stock,
    IF(t.inventory_strategy='SINGLE',t.locked_stock,COALESCE(b.locked_stock,0)) locked_stock,
    IF(t.inventory_strategy='SINGLE',t.sold_stock,COALESCE(b.sold_stock,0)) sold_stock,
    t.inventory_strategy,t.bucket_count,COALESCE(b.bucket_rows,0) bucket_rows,
    IF(t.inventory_strategy='SINGLE',t.total_stock,COALESCE(b.total_stock,0)) actual_total_stock
FROM ticket_sku t LEFT JOIN (
    SELECT sku_id,COUNT(*) bucket_rows,SUM(total_stock) total_stock,
        SUM(locked_stock) locked_stock,SUM(sold_stock) sold_stock
    FROM ticket_stock_bucket GROUP BY sku_id
) b ON b.sku_id=t.id;
