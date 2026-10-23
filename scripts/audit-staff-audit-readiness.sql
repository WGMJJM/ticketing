-- 只读预检，兼容空库、V6、成功V7和部分失败现场；SET/PREPARE只影响当前会话。
-- STOP必须停止部署，不自动repair、删除数据或更改全局变量。
SET @ticketing_has_history=(SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='flyway_schema_history');
SET @ticketing_failed=0, @ticketing_v7=0, @ticketing_latest=NULL;
SET @ticketing_sql=IF(@ticketing_has_history=1,
 'SELECT SUM(success=0),SUM(version=''7'' AND success=1),MAX(IF(success=1,CAST(version AS UNSIGNED),NULL)) INTO @ticketing_failed,@ticketing_v7,@ticketing_latest FROM flyway_schema_history',
 'SELECT 0,0,NULL INTO @ticketing_failed,@ticketing_v7,@ticketing_latest');
PREPARE ticketing_preflight FROM @ticketing_sql;
EXECUTE ticketing_preflight;
DEALLOCATE PREPARE ticketing_preflight;
SET @ticketing_sql=IF(@ticketing_has_history=1,'SELECT version,script,checksum,success,installed_on FROM flyway_schema_history ORDER BY installed_rank','SELECT ''EMPTY_HISTORY'' AS history_state');
PREPARE ticketing_preflight FROM @ticketing_sql;
EXECUTE ticketing_preflight;
DEALLOCATE PREPARE ticketing_preflight;

SET @ticketing_v7_tables=(SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('staff_session_permission','admin_audit_log'));
SET @ticketing_audit_triggers=(SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND event_object_table='admin_audit_log' AND action_timing='BEFORE'
 AND ((trigger_name='admin_audit_no_update' AND event_manipulation='UPDATE') OR (trigger_name='admin_audit_no_delete' AND event_manipulation='DELETE')) AND action_statement LIKE '%SIGNAL SQLSTATE%45000%');
SELECT CASE
 WHEN @ticketing_failed>0 THEN 'STOP_FAILED_MIGRATION'
 WHEN @ticketing_v7>0 AND (@ticketing_v7_tables<>2 OR @ticketing_audit_triggers<>2) THEN 'STOP_V7_STRUCTURE'
 WHEN @ticketing_v7>0 THEN 'V7_APPLIED_REVIEW_DETAILS'
 WHEN @ticketing_v7_tables>0 THEN 'STOP_EXISTING_V7_OBJECT'
 WHEN @ticketing_latest=6 THEN 'V6_READY_FOR_REVIEW'
 WHEN @ticketing_latest IS NULL AND NOT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history') THEN 'EMPTY_DATABASE'
 ELSE 'STOP_UNEXPECTED_VERSION' END AS migration_state;

SELECT @@version AS mysql_version,@@log_bin AS log_bin,@@log_bin_trust_function_creators AS log_bin_trust_function_creators,
 CASE WHEN @ticketing_v7>0 AND @ticketing_failed=0 THEN 'TRIGGER_POLICY_NOT_REQUIRED_FOR_APPLIED_V7'
 WHEN @@log_bin=0 OR @@log_bin_trust_function_creators=1 THEN 'TRIGGER_POLICY_READY'
 ELSE 'STOP_TRIGGER_PRIVILEGE_REVIEW' END AS trigger_policy;
SHOW GRANTS;

-- 详细输出须与原V7生成的参考结构比较，不能只凭表名存在认定成功。
SELECT table_name,column_name,column_type,is_nullable,column_default,extra FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name IN ('staff_session_permission','admin_audit_log') ORDER BY table_name,ordinal_position;
SELECT table_name,index_name,non_unique,seq_in_index,column_name FROM information_schema.statistics
 WHERE table_schema=DATABASE() AND table_name IN ('staff_session_permission','admin_audit_log') ORDER BY table_name,index_name,seq_in_index;
SELECT table_name,constraint_name,column_name,referenced_table_name,referenced_column_name FROM information_schema.key_column_usage
 WHERE constraint_schema=DATABASE() AND table_name IN ('staff_session_permission','admin_audit_log') ORDER BY table_name,constraint_name,ordinal_position;
SELECT tc.table_name,tc.constraint_name,cc.check_clause FROM information_schema.table_constraints tc
 JOIN information_schema.check_constraints cc ON cc.constraint_schema=tc.constraint_schema AND cc.constraint_name=tc.constraint_name
 WHERE tc.constraint_schema=DATABASE() AND tc.constraint_name IN ('ck_user_role','ck_permission_time');
SELECT trigger_name,event_manipulation,action_timing,action_statement FROM information_schema.triggers
 WHERE trigger_schema=DATABASE() AND event_object_table='admin_audit_log' ORDER BY trigger_name;
SET @ticketing_sql=IF(@ticketing_v7_tables=2,'SELECT ''staff_session_permission'' AS table_name,COUNT(*) AS row_count FROM staff_session_permission UNION ALL SELECT ''admin_audit_log'',COUNT(*) FROM admin_audit_log','SELECT ''V7_TABLES_INCOMPLETE'' AS row_count_state');
PREPARE ticketing_preflight FROM @ticketing_sql;
EXECUTE ticketing_preflight;
DEALLOCATE PREPARE ticketing_preflight;
