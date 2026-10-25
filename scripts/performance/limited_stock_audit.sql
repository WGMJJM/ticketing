-- @limited_sku is bound by the guarded runner, never a hardcoded fixture ID.
SELECT 'limited_supply_bounds' invariant, COUNT(*) violation_count
FROM ticket_stock_balance WHERE id=@limited_sku AND
 (total_stock<>100 OR inventory_strategy<>'SINGLE' OR locked_stock<0 OR sold_stock<0
  OR locked_stock+sold_stock>100);
SELECT 'limited_active_units' invariant, GREATEST(COALESCE(SUM(i.quantity),0)-100,0) violation_count
FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id
WHERE i.sku_id=@limited_sku AND o.status IN ('PENDING','PAID');
SELECT 'limited_one_request_per_buyer' invariant, COUNT(*) violation_count FROM (
 SELECT user_id FROM purchase_request WHERE sku_id=@limited_sku
 GROUP BY user_id HAVING COUNT(*)<>1
) duplicates;
SELECT 'limited_one_order_per_buyer' invariant, COUNT(*) violation_count FROM (
 SELECT o.user_id FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id
 WHERE i.sku_id=@limited_sku GROUP BY o.user_id HAVING COUNT(*)<>1
) duplicates;
SELECT 'limited_order_binding' invariant, COUNT(*) violation_count
FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id
LEFT JOIN purchase_request p ON p.order_id=o.id
WHERE i.sku_id=@limited_sku AND
 (p.id IS NULL OR p.status<>'SUCCEEDED' OR p.user_id<>o.user_id OR p.quantity<>1
 OR p.sku_id<>i.sku_id OR i.quantity<>1 OR i.unit_price_cents<>12800
 OR o.total_amount_cents<>12800 OR o.session_id<>(SELECT session_id FROM ticket_sku WHERE id=@limited_sku));
SELECT 'limited_failed_no_order' invariant, COUNT(*) violation_count
FROM purchase_request WHERE sku_id=@limited_sku AND status<>'SUCCEEDED' AND order_id IS NOT NULL;
SELECT 'limited_account_state' invariant, COUNT(*) violation_count
FROM purchase_request p JOIN user_account u ON u.id=p.user_id
WHERE p.sku_id=@limited_sku AND (u.role<>'USER' OR u.status<>'ACTIVE' OR p.quantity<>1);
SELECT 'limited_no_payment_or_ticket' invariant,
 (SELECT COUNT(*) FROM payment_record)+(SELECT COUNT(*) FROM electronic_ticket) violation_count;
SELECT 'limited_no_waitlist' invariant, COUNT(*) violation_count FROM waitlist_entry;
