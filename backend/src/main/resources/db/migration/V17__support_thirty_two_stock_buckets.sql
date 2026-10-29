-- 只扩展合法范围；保留历史分桶、库存与不可变订单分配，不自动切换任何票档。
ALTER TABLE ticket_sku
    DROP CHECK ck_sku_inventory_strategy,
    ADD CONSTRAINT ck_sku_inventory_strategy CHECK (
        (inventory_strategy='SINGLE' AND bucket_count=0)
        OR (inventory_strategy='BUCKETED' AND bucket_count BETWEEN 1 AND 32
            AND locked_stock=0 AND sold_stock=0)
    );

ALTER TABLE ticket_stock_bucket
    DROP CHECK ck_bucket_number,
    ADD CONSTRAINT ck_bucket_number CHECK (bucket_no BETWEEN 0 AND 31);

ALTER TABLE stock_strategy_switch
    DROP CHECK ck_switch_buckets,
    ADD CONSTRAINT ck_switch_buckets CHECK (bucket_count BETWEEN 1 AND 32);
