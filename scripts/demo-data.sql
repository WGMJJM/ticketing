-- 仅用于本地开发，人工执行；不属于 Flyway 通用迁移，不在生产环境导入。
-- 全部名称显式标记演示，不代表真实活动，不含票价、订单或库存。
SET time_zone = '+00:00';
START TRANSACTION;

INSERT INTO activity (title, category, city, venue, summary, description, status, published_at)
SELECT '【演示】城市回声音乐节', 'FESTIVAL', '上海', '虚构场地 · 江畔音乐公园',
       '演示活动：在江风与音乐里，度过一个属于热爱的周末。',
       '本活动为本地开发演示数据，不是真实演出，不接受购票。\n你可以查看场次安排并选择场次；票档与购票将在下一阶段开放。',
       'PUBLISHED', UTC_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM activity WHERE title = '【演示】城市回声音乐节');

INSERT INTO activity (title, category, city, venue, summary, description, status, published_at)
SELECT '【演示】今晚一起笑脱口秀', 'STAGE', '北京', '虚构场地 · 小剧场',
       '演示活动：卸下日常的忙碌，把这个夜晚交给笑声。',
       '本活动为虚构的脱口秀展示样例。所有场地和安排仅供工程联调，不构成真实演出信息。',
       'PUBLISHED', UTC_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM activity WHERE title = '【演示】今晚一起笑脱口秀');

INSERT INTO activity (title, category, city, venue, summary, description, status, published_at)
SELECT '【演示】光与城市艺术展', 'EXHIBITION', '杭州', '虚构场地 · 城市艺术空间',
       '演示活动：循着光线，在熟悉的城市里发现新的灵感。',
       '本活动为本地开发演示数据，展示活动介绍、场次和北京时间。当前不能购买票档。',
       'PUBLISHED', UTC_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM activity WHERE title = '【演示】光与城市艺术展');

INSERT INTO activity_session (activity_id, title, starts_at, ends_at, sale_starts_at)
SELECT id, '周六午后场（演示）', '2030-05-18 06:00:00', '2030-05-18 09:00:00', '2030-04-01 02:00:00'
FROM activity a WHERE a.title LIKE '【演示】%' AND a.title IN ('【演示】城市回声音乐节', '【演示】今晚一起笑脱口秀', '【演示】光与城市艺术展')
AND NOT EXISTS (SELECT 1 FROM activity_session s WHERE s.activity_id = a.id AND s.title = '周六午后场（演示）');

INSERT INTO activity_session (activity_id, title, starts_at, ends_at, sale_starts_at)
SELECT id, '周六晚场（演示）', '2030-05-18 11:00:00', '2030-05-18 13:00:00', '2030-04-01 02:00:00'
FROM activity a WHERE a.title = '【演示】城市回声音乐节'
AND NOT EXISTS (SELECT 1 FROM activity_session s WHERE s.activity_id = a.id AND s.title = '周六晚场（演示）');
COMMIT;
