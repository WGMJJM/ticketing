-- 部署前先只读运行scripts/audit-waitlist-relations.sql，发现不一致即停止，不自动修改历史业务数据。
-- 现有uk_item_order只覆盖order_id；MySQL8.4需要精确的复合唯一引用键。
ALTER TABLE ticket_order_item ADD UNIQUE KEY uk_item_order_sku (order_id, sku_id);
ALTER TABLE waitlist_entry
    ADD KEY idx_waitlist_order_sku (reserved_order_id, sku_id),
    ADD CONSTRAINT fk_waitlist_order_sku FOREIGN KEY (reserved_order_id, sku_id)
        REFERENCES ticket_order_item (order_id, sku_id);
