-- 只允许停写、备份、执行只读审计后切换。quotaLimit由原ORDER_SESSION_LIMIT提供。
-- 不设置外键：所有运行写入先锁账号和场次；避免隐式外键获取颠倒显式锁序。
CREATE TABLE user_session_quota (
    user_id BIGINT UNSIGNED NOT NULL,
    session_id BIGINT UNSIGNED NOT NULL,
    occupied_quantity INT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id,session_id),
    CONSTRAINT ck_user_session_quota_nonnegative CHECK (occupied_quantity >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户跨票档场次占额，PENDING及PAID计入';

-- 任一异常故意触发CHECK使Flyway停止；保留新表及失败历史，不改原业务事实、不自动repair。
INSERT INTO user_session_quota(user_id,session_id,occupied_quantity)
SELECT 0,0,-1 WHERE ${quotaLimit} < 1
 OR EXISTS (
    SELECT 1 FROM ticket_order o
    LEFT JOIN ticket_order_item i ON i.order_id=o.id
    LEFT JOIN user_account u ON u.id=o.user_id
    LEFT JOIN activity_session s ON s.id=o.session_id
    LEFT JOIN ticket_sku t ON t.id=i.sku_id
    WHERE i.id IS NULL OR u.id IS NULL OR s.id IS NULL OR t.id IS NULL
       OR i.session_id<>o.session_id OR t.session_id<>o.session_id OR i.quantity<=0
       OR o.status NOT IN ('PENDING','PAID','CANCELLED','EXPIRED','REFUNDED')
 ) OR EXISTS (
    SELECT 1 FROM ticket_order_item i LEFT JOIN ticket_order o ON o.id=i.order_id
    WHERE o.id IS NULL
 ) OR EXISTS (
    SELECT order_id FROM ticket_order_item GROUP BY order_id HAVING COUNT(*)<>1
 ) OR EXISTS (
    SELECT o.user_id,o.session_id FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id
    WHERE o.status IN ('PENDING','PAID') GROUP BY o.user_id,o.session_id
    HAVING SUM(i.quantity)>2147483647 OR SUM(i.quantity)>${quotaLimit}
 );

INSERT INTO user_session_quota(user_id,session_id,occupied_quantity)
SELECT o.user_id,o.session_id,SUM(i.quantity)
FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id
WHERE o.status IN ('PENDING','PAID')
GROUP BY o.user_id,o.session_id;

-- 全量双向对账，既检查遗漏，也检查额外占额；零行允许运行时按需创建。
INSERT INTO user_session_quota(user_id,session_id,occupied_quantity)
SELECT 0,0,-1 WHERE EXISTS (
    SELECT 1 FROM (
        SELECT o.user_id,o.session_id,SUM(i.quantity) quantity
        FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id
        WHERE o.status IN ('PENDING','PAID') GROUP BY o.user_id,o.session_id
    ) x LEFT JOIN user_session_quota q ON q.user_id=x.user_id AND q.session_id=x.session_id
    WHERE q.user_id IS NULL OR q.occupied_quantity<>x.quantity
 ) OR EXISTS (
    SELECT 1 FROM user_session_quota q LEFT JOIN (
        SELECT o.user_id,o.session_id,SUM(i.quantity) quantity
        FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id
        WHERE o.status IN ('PENDING','PAID') GROUP BY o.user_id,o.session_id
    ) x ON q.user_id=x.user_id AND q.session_id=x.session_id
    WHERE q.occupied_quantity<>COALESCE(x.quantity,0)
 );
