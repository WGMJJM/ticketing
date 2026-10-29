-- 仅供 V16 切换前只读执行；MQ、Redis 和旧实例仍须另行检查。
SELECT status, COUNT(*) AS event_count FROM outbox_event GROUP BY status ORDER BY status;
SELECT COUNT(*) AS invalid_or_unfinished_events FROM outbox_event
WHERE status <> 'SENT' OR aggregate_type <> 'TICKET_SKU'
   OR event_type <> 'WAITLIST_DISPATCH_REQUESTED' OR id >= 9223372036854775807;
SELECT COUNT(*) AS unresolved_purchase_requests FROM purchase_request WHERE status = 'QUEUED';
SELECT COUNT(*) AS initialized_skus FROM ticket_sku WHERE redis_inventory_initialized = TRUE;
SELECT COUNT(*) AS ticket_count FROM ticket_sku;
SELECT COUNT(*) AS order_count FROM ticket_order;
SELECT COUNT(*) AS allocation_count FROM order_stock_allocation;
