-- MySQL 只提交终态，不再使用购票 Outbox 或持久排队预算。
SELECT 'purchase_terminal_commit' AS invariant,COUNT(*) AS violation_count
 FROM purchase_request WHERE status='QUEUED';
SELECT 'purchase_order_relation' AS invariant,COUNT(*) AS violation_count
 FROM purchase_request p LEFT JOIN ticket_order o ON o.id=p.order_id
 LEFT JOIN ticket_order_item i ON i.order_id=o.id
 WHERE p.status='SUCCEEDED' AND (o.id IS NULL OR o.user_id<>p.user_id OR i.sku_id<>p.sku_id
 OR i.quantity<>p.quantity OR o.idempotency_operation<>'CREATE_ORDER'
 OR BINARY o.idempotency_key<>BINARY p.idempotency_key OR o.request_hash<>p.request_hash);
SELECT 'purchase_no_unbound_order' AS invariant,COUNT(*) AS violation_count
 FROM purchase_request p JOIN ticket_order o ON o.user_id=p.user_id
 AND o.idempotency_operation=p.idempotency_operation AND BINARY o.idempotency_key=BINARY p.idempotency_key
 WHERE p.status<>'SUCCEEDED' OR p.order_id<>o.id;
SELECT 'purchase_no_late_order' AS invariant,COUNT(*) AS violation_count
 FROM purchase_request p JOIN ticket_order o ON o.id=p.order_id
 WHERE p.status='SUCCEEDED' AND o.created_at>=p.deadline_at;
