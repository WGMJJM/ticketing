-- 部署前只读运行scripts/audit-refund-readiness.sql；不清空支付事实，不修改V1至V5。
ALTER TABLE ticket_order
    DROP CHECK ck_order_status,
    DROP CHECK ck_order_paid_time,
    ADD CONSTRAINT ck_order_status CHECK (status IN ('PENDING','PAID','CANCELLED','EXPIRED','REFUNDED')),
    ADD CONSTRAINT ck_order_paid_time CHECK (
        (status IN ('PAID','REFUNDED') AND paid_at IS NOT NULL)
        OR (status NOT IN ('PAID','REFUNDED') AND paid_at IS NULL)),
    ADD KEY idx_order_admin_created (created_at,id);

-- 全局订单分页与取消场次候选计划分别缺少排序和状态入口；保留原订单/到期/用户索引。
CREATE INDEX idx_session_status ON activity_session(status,id);

CREATE TABLE refund_record (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_id BIGINT UNSIGNED NOT NULL,
    refund_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payment_id BIGINT UNSIGNED NOT NULL,
    amount_cents BIGINT NOT NULL COMMENT '整单模拟退款金额，服务端快照',
    operator_id BIGINT UNSIGNED NULL COMMENT 'NULL表示场次取消系统任务',
    reason VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'SUCCEEDED',
    refunded_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_refund_order (order_id),
    UNIQUE KEY uk_refund_no (refund_no),
    CONSTRAINT fk_refund_order FOREIGN KEY (order_id) REFERENCES ticket_order(id),
    CONSTRAINT fk_refund_payment FOREIGN KEY (payment_id) REFERENCES payment_record(id),
    CONSTRAINT fk_refund_operator FOREIGN KEY (operator_id) REFERENCES user_account(id),
    CONSTRAINT ck_refund_amount CHECK (amount_cents >= 0),
    CONSTRAINT ck_refund_status CHECK (status = 'SUCCEEDED'),
    CONSTRAINT ck_refund_reason CHECK (reason IN ('CUSTOMER_REQUEST','SERVICE_ADJUSTMENT','SESSION_CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='整单模拟退款，不代表真实资金退回';

CREATE TABLE order_aftercare (
    order_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(24) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    message VARCHAR(255) NOT NULL COMMENT '受控中文原因，不保存内部异常',
    attempt_count INT NOT NULL DEFAULT 1,
    last_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    next_retry_at DATETIME(6) NULL,
    PRIMARY KEY (order_id),
    KEY idx_aftercare_retry (status,next_retry_at,order_id),
    CONSTRAINT fk_aftercare_order FOREIGN KEY (order_id) REFERENCES ticket_order(id),
    CONSTRAINT ck_aftercare_status CHECK (status IN ('RETRY','MANUAL_REQUIRED','COMPLETED')),
    CONSTRAINT ck_aftercare_attempt CHECK (attempt_count > 0),
    CONSTRAINT ck_aftercare_retry CHECK ((status='RETRY' AND next_retry_at IS NOT NULL)
        OR (status<>'RETRY' AND next_retry_at IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='场次善后及人工售后状态，保留失败事实';
