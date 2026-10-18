-- 在目标数据库只读执行。必须先检查结果，异常行仅报告，不自动改写。
SELECT w.id AS waitlist_id,w.user_id,w.sku_id,w.quantity,w.reserved_order_id,
       o.user_id AS order_user_id,o.session_id,i.sku_id AS order_sku_id,i.quantity AS order_quantity,
       t.session_id AS sku_session_id
FROM waitlist_entry w
LEFT JOIN ticket_order o ON o.id=w.reserved_order_id
LEFT JOIN ticket_order_item i ON i.order_id=w.reserved_order_id
JOIN ticket_sku t ON t.id=w.sku_id
WHERE w.reserved_order_id IS NOT NULL
  AND (o.id IS NULL OR i.id IS NULL OR o.user_id<>w.user_id OR i.sku_id<>w.sku_id
       OR i.quantity<>w.quantity OR o.session_id<>t.session_id);
