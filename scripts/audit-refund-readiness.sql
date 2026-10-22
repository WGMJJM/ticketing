-- V6升级前只读审计；只报告异常，停止部署并人工核对，禁止自动删除/覆盖/repair。
-- 空结果为通过。历史支付缺票同样报告，不通过退款流程静默补票。
SELECT o.id AS order_id,'PAYMENT_SNAPSHOT_INVALID' AS issue
FROM ticket_order o LEFT JOIN payment_record p ON p.order_id=o.id
LEFT JOIN ticket_order_item i ON i.order_id=o.id
WHERE (o.status='PAID' AND (o.paid_at IS NULL OR p.id IS NULL OR p.status<>'SUCCEEDED'
    OR p.amount_cents<>o.total_amount_cents OR p.paid_at<>o.paid_at
    OR i.id IS NULL OR CAST(i.unit_price_cents AS DECIMAL(30,0))*i.quantity<>o.total_amount_cents))
   OR (o.status<>'PAID' AND o.paid_at IS NOT NULL) OR i.id IS NULL;

SELECT o.id AS order_id,'TICKET_COUNT_OR_STATE_INVALID' AS issue
FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id
LEFT JOIN electronic_ticket t ON t.order_id=o.id
WHERE o.status='PAID'
GROUP BY o.id,i.quantity
HAVING COUNT(t.id)<>i.quantity OR SUM(t.status NOT IN ('UNUSED','USED'))>0
    OR MIN(t.sequence_no)<>1 OR MAX(t.sequence_no)<>i.quantity;

SELECT id AS sku_id,'STOCK_INVALID' AS issue FROM ticket_sku
WHERE locked_stock<0 OR sold_stock<0 OR CAST(locked_stock AS SIGNED)+sold_stock>total_stock;

-- 同时执行audit-waitlist-relations.sql检查V5历史关联，不能移除其复合外键。
