SELECT 'quota_order_totals' invariant,COUNT(*) violation_count FROM (
 SELECT o.user_id,o.session_id,SUM(i.quantity) quantity
 FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id
 WHERE o.status IN ('PENDING','PAID') GROUP BY o.user_id,o.session_id
) x LEFT JOIN user_session_quota q ON q.user_id=x.user_id AND q.session_id=x.session_id
WHERE q.user_id IS NULL OR q.occupied_quantity<>x.quantity;
SELECT 'quota_extra_or_orphan' invariant,COUNT(*) violation_count
FROM user_session_quota q LEFT JOIN user_account u ON u.id=q.user_id
LEFT JOIN activity_session s ON s.id=q.session_id LEFT JOIN (
 SELECT o.user_id,o.session_id,SUM(i.quantity) quantity
 FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id
 WHERE o.status IN ('PENDING','PAID') GROUP BY o.user_id,o.session_id
) x ON q.user_id=x.user_id AND q.session_id=x.session_id
WHERE u.id IS NULL OR s.id IS NULL OR q.occupied_quantity<0 OR q.occupied_quantity<>COALESCE(x.quantity,0);
