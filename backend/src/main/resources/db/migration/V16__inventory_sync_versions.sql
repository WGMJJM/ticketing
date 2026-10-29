-- 停止旧应用、排空旧购票及库存消息后才能切换；异常在任何持久 DDL 前阻断。
CREATE TEMPORARY TABLE inventory_sync_migration_guard (
    violations BIGINT NOT NULL CHECK (violations = 0)
);
INSERT INTO inventory_sync_migration_guard
SELECT COUNT(*) FROM outbox_event
WHERE status <> 'SENT' OR aggregate_type <> 'TICKET_SKU'
   OR event_type <> 'WAITLIST_DISPATCH_REQUESTED' OR id >= 9223372036854775807;
DROP TEMPORARY TABLE inventory_sync_migration_guard;

CREATE TABLE inventory_sync_version (
    sku_id BIGINT UNSIGNED NOT NULL,
    inventory_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (sku_id),
    CONSTRAINT fk_inventory_sync_sku FOREIGN KEY (sku_id) REFERENCES ticket_sku(id) ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='票档库存及销售资格同步版本';

-- 比旧 Redis inventorySequence 大一，使每个历史 SKU 经安全协议重新同步一次。
INSERT INTO inventory_sync_version(sku_id, inventory_version)
SELECT t.id, COALESCE(MAX(e.id),0)+1
FROM ticket_sku t LEFT JOIN outbox_event e
  ON e.aggregate_type='TICKET_SKU' AND e.event_type='WAITLIST_DISPATCH_REQUESTED' AND e.aggregate_id=t.id
GROUP BY t.id;

DROP TABLE outbox_event;
