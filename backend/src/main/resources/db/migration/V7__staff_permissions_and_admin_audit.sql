-- 保留旧角色、历史核销与支付退款事实，仅增加工作人员授权和追加式管理审计。
ALTER TABLE user_account
    DROP CHECK ck_user_role,
    ADD CONSTRAINT ck_user_role CHECK (role IN ('USER', 'ADMIN', 'CHECKIN_STAFF'));

CREATE TABLE staff_session_permission (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    staff_id BIGINT UNSIGNED NOT NULL,
    session_id BIGINT UNSIGNED NOT NULL,
    granted_by BIGINT UNSIGNED NOT NULL,
    granted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    revoked_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_staff_session (staff_id, session_id),
    KEY idx_permission_session (session_id, staff_id),
    KEY idx_permission_grantor (granted_by),
    CONSTRAINT fk_permission_staff FOREIGN KEY (staff_id) REFERENCES user_account (id),
    CONSTRAINT fk_permission_session FOREIGN KEY (session_id) REFERENCES activity_session (id),
    CONSTRAINT fk_permission_grantor FOREIGN KEY (granted_by) REFERENCES user_account (id),
    CONSTRAINT ck_permission_time CHECK (revoked_at IS NULL OR revoked_at >= granted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工作人员场次授权，撤销保留记录';

CREATE TABLE admin_audit_log (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    operator_id BIGINT UNSIGNED NOT NULL,
    action VARCHAR(40) NOT NULL,
    resource_type VARCHAR(24) NOT NULL,
    resource_id BIGINT UNSIGNED NOT NULL,
    occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    summary VARCHAR(200) NOT NULL,
    before_values JSON NOT NULL,
    after_values JSON NOT NULL,
    request_id CHAR(36) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_audit_operator_time (operator_id, occurred_at, id),
    KEY idx_audit_resource_time (resource_type, resource_id, occurred_at, id),
    KEY idx_audit_time (occurred_at, id),
    CONSTRAINT fk_audit_operator FOREIGN KEY (operator_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理成功操作审计，仅追加，不保存凭据';

CREATE TRIGGER admin_audit_no_update BEFORE UPDATE ON admin_audit_log
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '审计记录只允许追加';
CREATE TRIGGER admin_audit_no_delete BEFORE DELETE ON admin_audit_log
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '审计记录只允许追加';
