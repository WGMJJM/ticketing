-- 迁移前只读；调用连接先SET @ticketing_quota_limit为部署使用的ORDER_SESSION_LIMIT，禁止省略。
SELECT 'STOP_QUOTA_LIMIT_REQUIRED' audit,
       IF(@ticketing_quota_limit IS NOT NULL AND @ticketing_quota_limit>=1,0,1) anomaly_count;
SELECT 'STOP_QUOTA_ORDER_RELATIONS' audit,COUNT(*) anomaly_count
FROM ticket_order o LEFT JOIN ticket_order_item i ON i.order_id=o.id
LEFT JOIN user_account u ON u.id=o.user_id LEFT JOIN activity_session s ON s.id=o.session_id
LEFT JOIN ticket_sku t ON t.id=i.sku_id
WHERE i.id IS NULL OR u.id IS NULL OR s.id IS NULL OR t.id IS NULL OR i.quantity<=0
 OR i.session_id<>o.session_id OR t.session_id<>o.session_id
 OR o.status NOT IN ('PENDING','PAID','CANCELLED','EXPIRED','REFUNDED');
SELECT 'STOP_QUOTA_ORPHAN_ITEMS' audit,COUNT(*) anomaly_count
FROM ticket_order_item i LEFT JOIN ticket_order o ON o.id=i.order_id WHERE o.id IS NULL;
SELECT 'STOP_QUOTA_DUPLICATE_ITEMS' audit,COUNT(*) anomaly_count
FROM (SELECT order_id FROM ticket_order_item GROUP BY order_id HAVING COUNT(*)<>1) x;
SELECT 'STOP_QUOTA_OVERFLOW_OR_LIMIT' audit,COUNT(*) anomaly_count FROM (
 SELECT o.user_id,o.session_id,SUM(i.quantity) quantity FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id
 WHERE o.status IN ('PENDING','PAID') GROUP BY o.user_id,o.session_id
 HAVING SUM(i.quantity)>2147483647 OR SUM(i.quantity)>@ticketing_quota_limit
) x;
