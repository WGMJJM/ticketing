-- 仅在 ticketing-jmeter 的 ticketingjmeter 库执行；只读统计，不清理任何业务数据。
-- 替换以下四项：幂等键前缀必须对应本轮 -JbatchId，时间从 MySQL UTC 查询取得。
SET time_zone = '+00:00';
SET @batch_prefix = 'jmeter-REPLACE_WITH_BATCH_ID-';
SET @window_start_utc = '2026-01-01 00:00:00.000000';
SET @window_end_utc = '2026-01-01 00:01:00.000000';
SET @sku_id = 0;
SET @window_seconds = TIMESTAMPDIFF(MICROSECOND, @window_start_utc, @window_end_utc) / 1000000.0;

-- 窗口内已受理申请数，以及当前终态；失败/超时和仍排队申请只用于解释有效性。
SELECT
    COUNT(*) AS accepted_in_window,
    SUM(status = 'SUCCEEDED') AS succeeded_requests,
    SUM(status = 'FAILED') AS failed_requests,
    SUM(status = 'EXPIRED') AS expired_requests,
    SUM(status = 'QUEUED') AS still_queued_after_drain
FROM purchase_request
WHERE idempotency_operation = 'CREATE_ORDER'
  AND idempotency_key LIKE CONCAT(@batch_prefix, '%')
  AND sku_id = @sku_id
  AND accepted_at >= @window_start_utc
  AND accepted_at < @window_end_utc;

-- 核心 KPI：正式窗口内创建成功的普通订单数 / 固定窗口秒数。
-- 不按最终订单状态过滤，故窗口内创建后仍 PENDING 或后来 EXPIRED 的订单仍计为创建成功。
SELECT
    @window_start_utc AS window_start_utc,
    @window_end_utc AS window_end_utc,
    @window_seconds AS window_seconds,
    COUNT(DISTINCT o.id) AS successful_order_count,
    ROUND(COUNT(DISTINCT o.id) / NULLIF(@window_seconds, 0), 3) AS successful_order_tps
FROM ticket_order o
JOIN ticket_order_item i ON i.order_id = o.id
JOIN purchase_request p ON p.order_id = o.id
WHERE o.idempotency_operation = 'CREATE_ORDER'
  AND o.idempotency_key LIKE CONCAT(@batch_prefix, '%')
  AND p.idempotency_operation = 'CREATE_ORDER'
  AND p.idempotency_key = o.idempotency_key
  AND p.status = 'SUCCEEDED'
  AND p.sku_id = @sku_id
  AND i.sku_id = @sku_id
  AND o.created_at >= @window_start_utc
  AND o.created_at < @window_end_utc;

-- 排空阶段（正式窗口结束后创建）的订单单独列示，不混入窗口 TPS。
SELECT COUNT(DISTINCT o.id) AS drain_orders_after_window
FROM ticket_order o
JOIN ticket_order_item i ON i.order_id = o.id
JOIN purchase_request p ON p.order_id = o.id
WHERE o.idempotency_operation = 'CREATE_ORDER'
  AND o.idempotency_key LIKE CONCAT(@batch_prefix, '%')
  AND p.status = 'SUCCEEDED'
  AND p.sku_id = @sku_id
  AND i.sku_id = @sku_id
  AND o.created_at >= @window_end_utc;

-- 本轮全部受理事实按当前状态汇总，包含窗口外排空结果。
SELECT status, COALESCE(failure_code, '') AS failure_code, COUNT(*) AS request_count
FROM purchase_request
WHERE idempotency_operation = 'CREATE_ORDER'
  AND idempotency_key LIKE CONCAT(@batch_prefix, '%')
  AND sku_id = @sku_id
GROUP BY status, failure_code
ORDER BY status, failure_code;

-- 受理成功与正式订单双向关联异常数；正常结果必须为 0。
SELECT COUNT(*) AS success_link_mismatch_count
FROM purchase_request p
LEFT JOIN ticket_order o ON o.id = p.order_id
WHERE p.idempotency_operation = 'CREATE_ORDER'
  AND p.idempotency_key LIKE CONCAT(@batch_prefix, '%')
  AND p.sku_id = @sku_id
  AND ((p.status = 'SUCCEEDED' AND (o.id IS NULL OR o.user_id <> p.user_id
       OR o.idempotency_operation <> 'CREATE_ORDER' OR o.idempotency_key <> p.idempotency_key))
       OR (p.status <> 'SUCCEEDED' AND p.order_id IS NOT NULL));

-- 正式订单幂等键重复数；数据库唯一约束下正常结果必须为 0。
SELECT COUNT(*) AS duplicate_order_idempotency_keys
FROM (
    SELECT user_id, idempotency_operation, idempotency_key
    FROM ticket_order
    WHERE idempotency_operation = 'CREATE_ORDER'
      AND idempotency_key LIKE CONCAT(@batch_prefix, '%')
    GROUP BY user_id, idempotency_operation, idempotency_key
    HAVING COUNT(*) > 1
) duplicates;

-- MySQL 权威库存约束与本轮申请关联的 Outbox 投递状态。
SELECT id AS sku_id, total_stock, locked_stock, sold_stock,
       (CAST(locked_stock AS SIGNED) + sold_stock <= total_stock) AS stock_invariant_holds
FROM ticket_sku
WHERE id = @sku_id;

SELECT e.status, COUNT(*) AS outbox_event_count
FROM purchase_request p
JOIN outbox_event e ON e.event_id = CONCAT('purchase-', p.id)
WHERE p.idempotency_operation = 'CREATE_ORDER'
  AND p.idempotency_key LIKE CONCAT(@batch_prefix, '%')
  AND p.sku_id = @sku_id
GROUP BY e.status
ORDER BY e.status;
