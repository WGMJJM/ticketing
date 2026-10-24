-- 仅针对独立初始化且没有历史库存差额的 ticketing_perf 库。
-- 每条 SELECT 返回 violation_count；压测工具要求全部为零。
SELECT 'stock_bounds' AS invariant, COUNT(*) AS violation_count FROM ticket_sku
 WHERE locked_stock<0 OR sold_stock<0 OR total_stock<CAST(locked_stock AS SIGNED)+sold_stock;
SELECT 'stock_order_totals' AS invariant, COUNT(*) AS violation_count FROM ticket_sku t
 LEFT JOIN (SELECT i.sku_id,SUM(IF(o.status='PENDING',i.quantity,0)) locked_qty,
 SUM(IF(o.status='PAID',i.quantity,0)) sold_qty FROM ticket_order_item i
 JOIN ticket_order o ON o.id=i.order_id GROUP BY i.sku_id) q ON q.sku_id=t.id
 WHERE t.locked_stock<>COALESCE(q.locked_qty,0) OR t.sold_stock<>COALESCE(q.sold_qty,0);
SELECT 'payment_snapshot' AS invariant, COUNT(*) AS violation_count FROM ticket_order o
 LEFT JOIN payment_record p ON p.order_id=o.id
 LEFT JOIN refund_record r ON r.order_id=o.id
 WHERE (o.status IN ('PAID','REFUNDED') AND (p.id IS NULL OR p.status<>'SUCCEEDED' OR p.amount_cents<>o.total_amount_cents OR NOT (p.paid_at<=>o.paid_at)))
 OR (o.status NOT IN ('PAID','REFUNDED') AND p.status='SUCCEEDED')
 OR (o.status='REFUNDED' AND (r.id IS NULL OR r.status<>'SUCCEEDED' OR r.payment_id<>p.id OR r.amount_cents<>o.total_amount_cents))
 OR (o.status<>'REFUNDED' AND r.id IS NOT NULL);
SELECT 'order_snapshot' AS invariant, COUNT(*) AS violation_count FROM ticket_order o
 LEFT JOIN ticket_order_item i ON i.order_id=o.id
 WHERE i.id IS NULL OR i.session_id<>o.session_id OR o.total_amount_cents<>i.unit_price_cents*i.quantity;
SELECT 'session_quota' AS invariant, COUNT(*) AS violation_count FROM
 (SELECT o.user_id,o.session_id FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id
 WHERE o.status IN ('PENDING','PAID') GROUP BY o.user_id,o.session_id HAVING SUM(i.quantity)>4) q;
SELECT 'waitlist_relation' AS invariant, COUNT(*) AS violation_count FROM waitlist_entry w
 LEFT JOIN ticket_order o ON o.id=w.reserved_order_id LEFT JOIN ticket_order_item i ON i.order_id=o.id
 JOIN ticket_sku t ON t.id=w.sku_id
 WHERE (w.status IN ('ALLOCATED','COMPLETED') AND o.id IS NULL)
 OR (w.status IN ('WAITING','CANCELLED') AND w.reserved_order_id IS NOT NULL)
 OR (w.reserved_order_id IS NOT NULL AND (o.id IS NULL OR o.user_id<>w.user_id OR o.session_id<>t.session_id
 OR i.sku_id<>w.sku_id OR i.quantity<>w.quantity OR o.idempotency_operation<>'WAITLIST_CONFIRM'
 OR o.idempotency_key<>CONCAT('waitlist-',w.id)
 OR (w.status='ALLOCATED' AND o.status<>'PENDING') OR (w.status='COMPLETED' AND o.status NOT IN ('PAID','REFUNDED'))
 OR (w.status='EXPIRED' AND o.status NOT IN ('CANCELLED','EXPIRED'))));
SELECT 'fifo_allocation_order' AS invariant, COUNT(*) AS violation_count FROM waitlist_entry a
 JOIN waitlist_entry b ON b.sku_id=a.sku_id AND b.id>a.id
 WHERE b.reserved_order_id IS NOT NULL AND
 ((a.reserved_order_id IS NOT NULL AND a.reserved_order_id>b.reserved_order_id) OR a.status='WAITING');
SELECT 'ticket_quantity' AS invariant, COUNT(*) AS violation_count FROM ticket_order_item i
 JOIN ticket_order o ON o.id=i.order_id LEFT JOIN
 (SELECT order_item_id,COUNT(*) n,MIN(sequence_no) lo,MAX(sequence_no) hi FROM electronic_ticket GROUP BY order_item_id) t ON t.order_item_id=i.id
 WHERE (o.status IN ('PAID','REFUNDED') AND (COALESCE(t.n,0)<>i.quantity OR t.lo<>1 OR t.hi<>i.quantity))
 OR (o.status NOT IN ('PAID','REFUNDED') AND COALESCE(t.n,0)<>0);
SELECT 'ticket_relation' AS invariant, COUNT(*) AS violation_count FROM electronic_ticket t
 JOIN ticket_order o ON o.id=t.order_id JOIN ticket_order_item i ON i.id=t.order_item_id
 WHERE t.owner_id<>o.user_id OR t.order_id<>i.order_id OR t.sku_id<>i.sku_id
 OR (o.status='REFUNDED' AND t.status<>'VOID') OR (o.status='PAID' AND t.status='VOID')
 OR (t.status='USED' AND (t.used_at IS NULL OR t.checked_by IS NULL))
 OR (t.status<>'USED' AND (t.used_at IS NOT NULL OR t.checked_by IS NOT NULL));
SELECT 'duplicate_payment_effect' AS invariant, COUNT(*) AS violation_count FROM
 (SELECT order_id FROM payment_record WHERE status='SUCCEEDED' GROUP BY order_id HAVING COUNT(*)>1) p;
SELECT 'duplicate_active_waitlist' AS invariant, COUNT(*) AS violation_count FROM
 (SELECT user_id,sku_id FROM waitlist_entry WHERE status IN ('WAITING','ALLOCATED') GROUP BY user_id,sku_id HAVING COUNT(*)>1) w;
