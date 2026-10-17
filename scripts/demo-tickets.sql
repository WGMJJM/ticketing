-- 仅供本地开发手动导入，不是 Flyway 通用迁移；所有活动与票档均为虚构演示。
SET time_zone = '+00:00';
START TRANSACTION;
INSERT INTO activity (title, category, city, venue, summary, description, status, published_at)
SELECT '【演示】票档与库存体验场', 'CONCERT', '上海', '虚构场地 · 现场体验空间',
       '演示活动：查看不同票档、人民币价格、剩余数量和服务端销售状态。',
       '这是一场虚构的票档功能演示，可体验真实下单、模拟支付、超时关闭与主动取消，不产生实际扣款或实际入场凭证。所有时间均按北京时间展示。',
       'PUBLISHED', UTC_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM activity WHERE title = '【演示】票档与库存体验场');
INSERT INTO activity_session (activity_id, title, starts_at, ends_at, sale_starts_at)
SELECT id, '票档状态体验场（演示）', DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 30 DAY),
       DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 31 DAY), DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 2 DAY)
FROM activity a WHERE a.title = '【演示】票档与库存体验场'
AND NOT EXISTS (SELECT 1 FROM activity_session s WHERE s.activity_id = a.id AND s.title = '票档状态体验场（演示）');
INSERT INTO ticket_sku (session_id, name, price_cents, total_stock, sale_starts_at, sale_ends_at, status)
SELECT s.id, '标准票（演示）', 29900, 100, DATE_ADD(s.sale_starts_at, INTERVAL 1 HOUR), DATE_SUB(s.starts_at, INTERVAL 1 DAY), 'ON_SALE'
FROM activity_session s JOIN activity a ON a.id = s.activity_id
WHERE a.title = '【演示】票档与库存体验场' AND s.title = '票档状态体验场（演示）'
AND NOT EXISTS (SELECT 1 FROM ticket_sku t WHERE t.session_id = s.id AND t.name = '标准票（演示）');
INSERT INTO ticket_sku (session_id, name, price_cents, total_stock, sale_starts_at, sale_ends_at, status)
SELECT s.id, '零配额票（演示）', 19900, 0, DATE_ADD(s.sale_starts_at, INTERVAL 1 HOUR), DATE_SUB(s.starts_at, INTERVAL 1 DAY), 'ON_SALE'
FROM activity_session s JOIN activity a ON a.id = s.activity_id
WHERE a.title = '【演示】票档与库存体验场' AND s.title = '票档状态体验场（演示）'
AND NOT EXISTS (SELECT 1 FROM ticket_sku t WHERE t.session_id = s.id AND t.name = '零配额票（演示）');
INSERT INTO ticket_sku (session_id, name, price_cents, total_stock, sale_starts_at, sale_ends_at, status)
SELECT s.id, '即将开售票（演示）', 39900, 50, DATE_SUB(s.starts_at, INTERVAL 2 DAY), DATE_SUB(s.starts_at, INTERVAL 1 DAY), 'ON_SALE'
FROM activity_session s JOIN activity a ON a.id = s.activity_id
WHERE a.title = '【演示】票档与库存体验场' AND s.title = '票档状态体验场（演示）'
AND NOT EXISTS (SELECT 1 FROM ticket_sku t WHERE t.session_id = s.id AND t.name = '即将开售票（演示）');
INSERT INTO ticket_sku (session_id, name, price_cents, total_stock, sale_starts_at, sale_ends_at, status)
SELECT s.id, '停售票（演示）', 9900, 20, s.sale_starts_at, DATE_ADD(s.sale_starts_at, INTERVAL 1 HOUR), 'ON_SALE'
FROM activity_session s JOIN activity a ON a.id = s.activity_id
WHERE a.title = '【演示】票档与库存体验场' AND s.title = '票档状态体验场（演示）'
AND NOT EXISTS (SELECT 1 FROM ticket_sku t WHERE t.session_id = s.id AND t.name = '停售票（演示）');
COMMIT;
