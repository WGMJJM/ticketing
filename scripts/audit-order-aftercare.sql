-- V6之后的只读一致性审计；库存历史差额另做带初始快照的核对，不直接断言全库等于本轮订单。
SELECT o.id AS order_id,'REFUND_FACT_INVALID' issue
FROM ticket_order o LEFT JOIN refund_record r ON r.order_id=o.id
LEFT JOIN payment_record p ON p.order_id=o.id
WHERE (o.status='REFUNDED' AND (r.id IS NULL OR p.id IS NULL OR p.status<>'SUCCEEDED'
    OR r.payment_id<>p.id OR r.amount_cents<>o.total_amount_cents
    OR p.amount_cents<>o.total_amount_cents OR o.paid_at IS NULL OR p.paid_at<>o.paid_at))
 OR (r.id IS NOT NULL AND o.status<>'REFUNDED');

SELECT o.id AS order_id,'REFUNDED_TICKETS_INVALID' issue
FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id
LEFT JOIN electronic_ticket t ON t.order_id=o.id WHERE o.status='REFUNDED'
GROUP BY o.id,i.quantity HAVING COUNT(t.id)<>i.quantity OR SUM(t.status<>'VOID')>0;

SELECT o.id AS order_id,'PAID_FACT_INVALID' issue
FROM ticket_order o LEFT JOIN payment_record p ON p.order_id=o.id
WHERE o.status='PAID' AND (p.id IS NULL OR p.status<>'SUCCEEDED'
 OR p.amount_cents<>o.total_amount_cents OR p.paid_at<>o.paid_at);

SELECT id AS sku_id,'STOCK_INVALID' issue FROM ticket_sku
WHERE locked_stock<0 OR sold_stock<0 OR CAST(locked_stock AS SIGNED)+sold_stock>total_stock;

SELECT w.id AS waitlist_id,'REFUND_WAITLIST_INVALID' issue
FROM waitlist_entry w JOIN ticket_order o ON o.id=w.reserved_order_id
JOIN ticket_order_item i ON i.order_id=o.id
WHERE o.status='REFUNDED' AND (w.status<>'COMPLETED' OR w.user_id<>o.user_id
 OR w.sku_id<>i.sku_id OR w.quantity<>i.quantity OR o.idempotency_operation<>'WAITLIST_CONFIRM'
 OR o.idempotency_key<>CONCAT('waitlist-',w.id));

-- 下面是运营待办清单，并非自动修复命令；MANUAL_REQUIRED保持原支付与已核销事实。
SELECT c.order_id,o.order_no,c.status,c.reason_code,c.message,c.attempt_count,c.last_attempt_at,c.next_retry_at
FROM order_aftercare c JOIN ticket_order o ON o.id=c.order_id
WHERE c.status IN ('MANUAL_REQUIRED','RETRY') ORDER BY c.last_attempt_at,c.order_id;
