-- 第十四阶段V8部署前只读检查。在停止业务写入并保留备份后执行；异常只报告并停止。
-- 本脚本适用于已成功应用V7的历史库；不得将输出用作自动repair或删除依据。
START TRANSACTION READ ONLY;
SELECT version,success FROM flyway_schema_history ORDER BY installed_rank;
SELECT 'STOP_ORDINARY_ORDER_ITEM_MISSING' AS audit,COUNT(*) AS anomaly_count
FROM ticket_order o LEFT JOIN ticket_order_item i ON i.order_id=o.id
WHERE o.idempotency_operation='CREATE_ORDER' AND i.id IS NULL;
SELECT 'STOP_ORDINARY_ORDER_HASH_MISMATCH' AS audit,COUNT(*) AS anomaly_count
FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id
WHERE o.idempotency_operation='CREATE_ORDER'
  AND o.request_hash<>UNHEX(SHA2(CONCAT(i.sku_id,':',i.quantity),256));
SELECT 'STOP_ORDINARY_ORDER_MULTIPLE_ITEMS' AS audit,COUNT(*) AS anomaly_count FROM (
  SELECT o.id FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id
  WHERE o.idempotency_operation='CREATE_ORDER' GROUP BY o.id HAVING COUNT(*)<>1
) unexpected;
COMMIT;
