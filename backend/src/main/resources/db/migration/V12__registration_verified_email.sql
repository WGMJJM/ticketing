-- 保留历史账号与原uk_user_username；多个历史空邮箱合法，不自动补造验证事实。
ALTER TABLE user_account
    ADD COLUMN email VARCHAR(254) COLLATE utf8mb4_0900_as_ci NULL COMMENT '注册邮箱，ASCII小写规范化，唯一且不区分大小写' AFTER username,
    ADD COLUMN email_verified_at DATETIME(6) NULL COMMENT '邮箱验证完成时间 UTC' AFTER email,
    ADD UNIQUE KEY uk_user_email (email),
    ADD CONSTRAINT ck_user_email_verified CHECK (
        (email IS NULL AND email_verified_at IS NULL)
        OR (email IS NOT NULL AND email_verified_at IS NOT NULL)
    );
