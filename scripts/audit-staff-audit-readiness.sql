-- V7升级前在已有Flyway历史的库中只读执行；空库由Flyway从V1创建。
-- 出现STOP结果必须停止升级并人工核对。保留失败历史和已提交DDL，不自动repair或清库。
SELECT version, script, success, installed_on
FROM flyway_schema_history
ORDER BY installed_rank;

SELECT CASE
    WHEN EXISTS(SELECT 1 FROM flyway_schema_history WHERE success=0)
        THEN 'STOP_FAILED_MIGRATION'
    WHEN EXISTS(SELECT 1 FROM flyway_schema_history WHERE version='7' AND success=1)
        THEN 'V7_ALREADY_APPLIED'
    WHEN (SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1)='6'
        THEN 'V6_READY_FOR_REVIEW'
    ELSE 'STOP_UNEXPECTED_VERSION'
END AS migration_state;

-- 开启binlog时，非SUPER迁移账号创建触发器需要此设置以及当前schema的TRIGGER权限。
-- 不在审计脚本内更改全局变量或授予权限，由数据库管理员按部署策略安排。
SELECT @@log_bin AS log_bin,
       @@log_bin_trust_function_creators AS log_bin_trust_function_creators,
       CASE WHEN @@log_bin=0 OR @@log_bin_trust_function_creators=1
           THEN 'TRIGGER_POLICY_READY'
           ELSE 'STOP_TRIGGER_PRIVILEGE_REVIEW'
       END AS trigger_policy;
SHOW GRANTS;

SELECT table_name, 'STOP_EXISTING_V7_OBJECT' AS issue
FROM information_schema.tables
WHERE table_schema=DATABASE()
  AND table_name IN ('staff_session_permission','admin_audit_log')
  AND NOT EXISTS(SELECT 1 FROM flyway_schema_history WHERE version='7' AND success=1);

SELECT trigger_name, event_manipulation, action_timing
FROM information_schema.triggers
WHERE trigger_schema=DATABASE()
  AND trigger_name IN ('admin_audit_no_update','admin_audit_no_delete')
ORDER BY trigger_name;
