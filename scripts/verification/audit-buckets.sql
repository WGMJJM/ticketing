-- 在与既有库存/额度/候补/支付/退款审计相同的一致性快照内执行。
SELECT 'bucket_strategy_totals' invariant,COUNT(*) violation_count FROM ticket_stock_balance
 WHERE (inventory_strategy='BUCKETED' AND (bucket_rows<>bucket_count OR actual_total_stock<>total_stock))
    OR (inventory_strategy='SINGLE' AND bucket_rows<>0);
SELECT 'bucket_bounds' invariant,COUNT(*) violation_count FROM ticket_stock_bucket
 WHERE total_stock<0 OR locked_stock<0 OR sold_stock<0 OR CAST(locked_stock AS SIGNED)+sold_stock>total_stock;
SELECT 'allocation_quantity' invariant,COUNT(*) violation_count FROM ticket_order_item i
 JOIN ticket_order o ON o.id=i.order_id JOIN ticket_sku t ON t.id=i.sku_id
 LEFT JOIN (SELECT order_id,sku_id,SUM(quantity) quantity FROM order_stock_allocation GROUP BY order_id,sku_id) a
 ON a.order_id=i.order_id AND a.sku_id=i.sku_id
 WHERE (t.inventory_strategy='BUCKETED' AND o.status IN('PENDING','PAID') AND COALESCE(a.quantity,0)<>i.quantity)
 OR (a.quantity IS NOT NULL AND a.quantity<>i.quantity);
SELECT 'bucket_order_totals' invariant,COUNT(*) violation_count FROM ticket_stock_bucket b
 LEFT JOIN (SELECT a.sku_id,a.bucket_no,SUM(IF(o.status='PENDING',a.quantity,0)) locked_stock,
 SUM(IF(o.status='PAID',a.quantity,0)) sold_stock FROM order_stock_allocation a
 JOIN ticket_order o ON o.id=a.order_id GROUP BY a.sku_id,a.bucket_no) x
 ON x.sku_id=b.sku_id AND x.bucket_no=b.bucket_no
 WHERE b.locked_stock<>COALESCE(x.locked_stock,0) OR b.sold_stock<>COALESCE(x.sold_stock,0);
SELECT 'allocation_relations' invariant,COUNT(*) violation_count FROM order_stock_allocation a
 LEFT JOIN ticket_order_item i ON i.order_id=a.order_id AND i.sku_id=a.sku_id
 LEFT JOIN ticket_stock_bucket b ON b.sku_id=a.sku_id AND b.bucket_no=a.bucket_no
 LEFT JOIN ticket_sku t ON t.id=a.sku_id
 WHERE i.id IS NULL OR b.sku_id IS NULL OR t.inventory_strategy<>'BUCKETED'
 OR a.bucket_no>=t.bucket_count OR a.quantity<1;
SELECT 'bucket_switch_receipt' invariant,COUNT(*) violation_count FROM ticket_sku t
 LEFT JOIN stock_strategy_switch s ON s.sku_id=t.id
 WHERE t.inventory_strategy='BUCKETED' AND (s.sku_id IS NULL OR s.bucket_count<>t.bucket_count);
