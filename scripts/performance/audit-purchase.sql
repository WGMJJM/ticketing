-- 随同原有审计在一个只读一致性快照内运行。
SELECT 'purchase_capacity' AS invariant,
 ABS((SELECT pending_count FROM purchase_capacity WHERE id=1)-
 (SELECT COUNT(*) FROM purchase_request WHERE status IN('QUEUED','PROCESSING'))) AS violation_count;
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
SELECT 'purchase_reliable_command' AS invariant,COUNT(*) AS violation_count
 FROM purchase_request p LEFT JOIN outbox_event e ON e.event_id=CONCAT('purchase-',p.id)
 WHERE p.status IN('QUEUED','PROCESSING') AND (e.id IS NULL OR e.event_type<>'PURCHASE_REQUEST_ACCEPTED'
 OR e.aggregate_type<>'PURCHASE_REQUEST' OR e.aggregate_id<>p.id);
SELECT 'purchase_exhausted_command_active' AS invariant,COUNT(*) AS violation_count
 FROM purchase_request p JOIN outbox_event e ON e.event_id=CONCAT('purchase-',p.id)
 WHERE p.status IN('QUEUED','PROCESSING') AND e.status='FAILED';
