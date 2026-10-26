-- 仅供本地开发手动导入，不是 Flyway 通用迁移；所有活动与票档均为虚构演示。
SET time_zone = '+00:00';
START TRANSACTION;
INSERT INTO activity (title, category, city, venue, summary, description, status, published_at)
SELECT '晚风来信·城市音乐会', 'CONCERT', '上海', '城市音乐现场',
       '让一首歌，接住城市晚风里的心事。',
       '在旋律与晚风里，给忙碌的生活留一段属于音乐的时间。\n选择场次后可查看票档、票价与余票，开售和停售时间以页面信息为准。',
       'PUBLISHED', UTC_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM activity WHERE title IN ('晚风来信·城市音乐会', '【演示】票档与库存体验场'));
INSERT INTO activity_session (activity_id, title, starts_at, ends_at, sale_starts_at)
SELECT id, '城市音乐会专场', DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 30 DAY),
       DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 31 DAY), DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 2 DAY)
FROM activity a WHERE a.title = '晚风来信·城市音乐会'
AND NOT EXISTS (SELECT 1 FROM activity_session s WHERE s.activity_id = a.id AND s.title IN ('城市音乐会专场', '票档状态体验场（演示）'));
INSERT INTO ticket_sku (session_id, name, price_cents, total_stock, sale_starts_at, sale_ends_at, status)
SELECT s.id, '标准票', 29900, 100, DATE_ADD(s.sale_starts_at, INTERVAL 1 HOUR), DATE_SUB(s.starts_at, INTERVAL 1 DAY), 'ON_SALE'
FROM activity_session s JOIN activity a ON a.id = s.activity_id
WHERE a.title = '晚风来信·城市音乐会' AND s.title = '城市音乐会专场'
AND NOT EXISTS (SELECT 1 FROM ticket_sku t WHERE t.session_id = s.id AND t.name IN ('标准票', '标准票（演示）'));
INSERT INTO ticket_sku (session_id, name, price_cents, total_stock, sale_starts_at, sale_ends_at, status)
SELECT s.id, '优选票', 19900, 0, DATE_ADD(s.sale_starts_at, INTERVAL 1 HOUR), DATE_SUB(s.starts_at, INTERVAL 1 DAY), 'ON_SALE'
FROM activity_session s JOIN activity a ON a.id = s.activity_id
WHERE a.title = '晚风来信·城市音乐会' AND s.title = '城市音乐会专场'
AND NOT EXISTS (SELECT 1 FROM ticket_sku t WHERE t.session_id = s.id AND t.name IN ('优选票', '零配额票（演示）'));
INSERT INTO ticket_sku (session_id, name, price_cents, total_stock, sale_starts_at, sale_ends_at, status)
SELECT s.id, '畅听票', 39900, 50, DATE_SUB(s.starts_at, INTERVAL 2 DAY), DATE_SUB(s.starts_at, INTERVAL 1 DAY), 'ON_SALE'
FROM activity_session s JOIN activity a ON a.id = s.activity_id
WHERE a.title = '晚风来信·城市音乐会' AND s.title = '城市音乐会专场'
AND NOT EXISTS (SELECT 1 FROM ticket_sku t WHERE t.session_id = s.id AND t.name IN ('畅听票', '即将开售票（演示）'));
INSERT INTO ticket_sku (session_id, name, price_cents, total_stock, sale_starts_at, sale_ends_at, status)
SELECT s.id, '特惠票', 9900, 20, s.sale_starts_at, DATE_ADD(s.sale_starts_at, INTERVAL 1 HOUR), 'ON_SALE'
FROM activity_session s JOIN activity a ON a.id = s.activity_id
WHERE a.title = '晚风来信·城市音乐会' AND s.title = '城市音乐会专场'
AND NOT EXISTS (SELECT 1 FROM ticket_sku t WHERE t.session_id = s.id AND t.name IN ('特惠票', '停售票（演示）'));
COMMIT;
