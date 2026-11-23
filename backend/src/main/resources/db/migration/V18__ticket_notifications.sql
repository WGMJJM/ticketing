CREATE TABLE ticket_notification (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    order_id BIGINT UNSIGNED NOT NULL,
    kind VARCHAR(24) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    read_at DATETIME(6) NULL,
    mail_next_at DATETIME(6) NULL,
    mail_attempts TINYINT UNSIGNED NOT NULL DEFAULT 0,
    mail_status VARCHAR(12) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_order_kind (order_id, kind),
    KEY idx_notification_inbox (user_id, id),
    KEY idx_notification_unread (user_id, read_at, id),
    KEY idx_notification_mail (mail_next_at, id),
    CONSTRAINT fk_notification_user FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT fk_notification_order FOREIGN KEY (order_id) REFERENCES ticket_order (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
