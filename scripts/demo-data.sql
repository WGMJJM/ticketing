-- 仅用于本地开发，人工执行；不属于 Flyway 通用迁移，不在生产环境导入。
-- 虚构的本地样例，页面使用正常产品文案，不代表真实活动，不含票价、订单或库存。
SET time_zone = '+00:00';
START TRANSACTION;

INSERT INTO activity (title, category, city, venue, summary, description, status, published_at)
SELECT '城市回声音乐节', 'FESTIVAL', '上海', '江畔音乐公园',
       '在江风与音乐里，度过一个属于热爱的周末。',
       '在开阔的江畔相聚，让音乐陪伴周末。\n请在下方查看场次安排，选择适合自己的时间。票档与开售状态以页面实时信息为准。',
       'PUBLISHED', UTC_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM activity WHERE title IN ('城市回声音乐节', '【演示】城市回声音乐节'));

INSERT INTO activity (title, category, city, venue, summary, description, status, published_at)
SELECT '今晚一起笑脱口秀', 'STAGE', '北京', '笑场小剧场',
       '卸下日常的忙碌，把这个夜晚交给笑声。',
       '从生活里的小事聊起，在轻松的现场里发现熟悉的共鸣。\n请按所选场次时间入场，票档与开售安排见下方信息。',
       'PUBLISHED', UTC_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM activity WHERE title IN ('今晚一起笑脱口秀', '【演示】今晚一起笑脱口秀'));

INSERT INTO activity (title, category, city, venue, summary, description, status, published_at)
SELECT '光与城市艺术展', 'EXHIBITION', '杭州', '城市艺术空间',
       '循着光线，在熟悉的城市里发现新的灵感。',
       '以光影、空间与城市日常为线索，放慢脚步，感受不同的观看方式。\n参观时间以所选场次为准，请提前查看开放安排。',
       'PUBLISHED', UTC_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM activity WHERE title IN ('光与城市艺术展', '【演示】光与城市艺术展'));

INSERT INTO activity_session (activity_id, title, starts_at, ends_at, sale_starts_at)
SELECT id, '周六午后场', '2030-05-18 06:00:00', '2030-05-18 09:00:00', '2030-04-01 02:00:00'
FROM activity a WHERE a.title IN ('城市回声音乐节', '今晚一起笑脱口秀', '光与城市艺术展')
AND NOT EXISTS (SELECT 1 FROM activity_session s WHERE s.activity_id = a.id AND s.title IN ('周六午后场', '周六午后场（演示）'));

INSERT INTO activity_session (activity_id, title, starts_at, ends_at, sale_starts_at)
SELECT id, '周六晚场', '2030-05-18 11:00:00', '2030-05-18 13:00:00', '2030-04-01 02:00:00'
FROM activity a WHERE a.title = '城市回声音乐节'
AND NOT EXISTS (SELECT 1 FROM activity_session s WHERE s.activity_id = a.id AND s.title IN ('周六晚场', '周六晚场（演示）'));
COMMIT;
