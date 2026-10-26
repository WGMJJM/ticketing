CREATE TABLE activity_like (
    user_id BIGINT UNSIGNED NOT NULL COMMENT '点赞用户',
    activity_id BIGINT UNSIGNED NOT NULL COMMENT '被点赞活动',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '点赞时间 UTC',
    UNIQUE KEY uk_activity_like_user_activity (user_id, activity_id),
    KEY idx_activity_like_activity_user (activity_id, user_id),
    CONSTRAINT fk_activity_like_user FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE CASCADE,
    CONSTRAINT fk_activity_like_activity FOREIGN KEY (activity_id) REFERENCES activity (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='活动点赞关系';

CREATE TABLE activity_follow (
    user_id BIGINT UNSIGNED NOT NULL COMMENT '关注用户',
    activity_id BIGINT UNSIGNED NOT NULL COMMENT '被关注活动',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '关注时间 UTC',
    UNIQUE KEY uk_activity_follow_user_activity (user_id, activity_id),
    KEY idx_activity_follow_user_created_activity (user_id, created_at, activity_id),
    KEY idx_activity_follow_activity_user (activity_id, user_id),
    CONSTRAINT fk_activity_follow_user FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE CASCADE,
    CONSTRAINT fk_activity_follow_activity FOREIGN KEY (activity_id) REFERENCES activity (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='活动关注关系';
