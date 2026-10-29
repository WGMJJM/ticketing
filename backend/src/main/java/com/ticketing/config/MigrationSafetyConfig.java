package com.ticketing.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

/** Flyway开始任何DDL前检查已知失败状态和触发器创建前提；不repair、不改历史。 */
@Configuration(proxyBeanMethods = false)
public class MigrationSafetyConfig {
    @Value("${ticketing.order.quota-migration-approved:false}")
    private boolean quotaMigrationApproved;
    @Value("${ticketing.inventory.migration-approved:false}")
    private boolean inventoryMigrationApproved;
    @Bean
    FlywayMigrationStrategy migrationSafetyStrategy() {
        return flyway -> {
            try (Connection connection = flyway.getConfiguration().getDataSource().getConnection()) {
                verify(connection);
            } catch (SQLException exception) {
                throw new IllegalStateException("数据库迁移只读预检失败，请检查私有诊断并停止部署", exception);
            }
            flyway.migrate();
        };
    }

    private void verify(Connection connection) throws SQLException {
        String schema = connection.getCatalog();
        boolean history;
        try (var tables = connection.getMetaData().getTables(schema, null, "flyway_schema_history", new String[]{"TABLE"})) {
            history = tables.next();
        }
        boolean appliedV7 = false;
        boolean appliedV8 = false;
        boolean appliedV9 = false;
        boolean appliedV16 = false;
        if (history) {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT version,success FROM flyway_schema_history ORDER BY installed_rank")) {
                while (rows.next()) {
                    if (!rows.getBoolean("success")) {
                        throw new IllegalStateException("STOP_FAILED_MIGRATION 迁移失败现场须按受控恢复流程处理，禁止自动repair");
                    }
                    if ("7".equals(rows.getString("version"))) appliedV7 = true;
                    if ("8".equals(rows.getString("version"))) appliedV8 = true;
                    if ("9".equals(rows.getString("version"))) appliedV9 = true;
                    if ("16".equals(rows.getString("version"))) appliedV16 = true;
                }
            }
        }
        if (history && !appliedV16 && scalar(connection, "SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name='outbox_event'") != 0
                && scalar(connection, "SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name='ticket_sku'") != 0) {
            if (!inventoryMigrationApproved && (scalar(connection, "SELECT COUNT(*) FROM ticket_sku") != 0
                    || scalar(connection, "SELECT COUNT(*) FROM outbox_event") != 0)) {
                throw new IllegalStateException("STOP_INVENTORY_SWITCH 须先停写、停止旧实例并排空旧消息和预扣，再显式批准库存同步迁移");
            }
            if (scalar(connection, "SELECT COUNT(*) FROM outbox_event WHERE status<>'SENT' "
                    + "OR aggregate_type<>'TICKET_SKU' OR event_type<>'WAITLIST_DISPATCH_REQUESTED'") != 0) {
                throw new IllegalStateException("STOP_INVENTORY_OUTBOX 库存事件未排空或存在未知事件，停止迁移");
            }
        }
        if (history && !appliedV9 && !quotaMigrationApproved
                && scalar(connection,"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='ticket_order'")!=0
                && scalar(connection,"SELECT COUNT(*) FROM ticket_order")!=0) {
            throw new IllegalStateException("STOP_QUOTA_SWITCH 历史库升级V9须先停写、备份及只读审计，再显式批准切换");
        }
        if (appliedV7 && !appliedV8 && scalar(connection,
                "SELECT COUNT(*) FROM ticket_order o LEFT JOIN ticket_order_item i ON i.order_id=o.id "
                + "WHERE o.idempotency_operation='CREATE_ORDER' AND i.id IS NULL") != 0) {
            throw new IllegalStateException("STOP_V8_ORDER_ITEMS 历史普通订单缺少明细，停止迁移并人工核对");
        }
        int objects = scalar(connection, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
                + "AND table_name IN ('staff_session_permission','admin_audit_log')");
        if (appliedV7) {
            int triggers = scalar(connection, "SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() "
                    + "AND event_object_table='admin_audit_log' AND action_timing='BEFORE' "
                    + "AND ((trigger_name='admin_audit_no_update' AND event_manipulation='UPDATE') "
                    + "OR (trigger_name='admin_audit_no_delete' AND event_manipulation='DELETE')) "
                    + "AND action_statement LIKE '%SIGNAL SQLSTATE%45000%'");
            if (objects != 2 || triggers != 2) {
                throw new IllegalStateException("STOP_V7_STRUCTURE 迁移历史与审计结构不一致，停止部署");
            }
            return;
        }
        if (objects != 0) throw new IllegalStateException("STOP_EXISTING_V7_OBJECT 存在未登记成功的V7对象，停止部署");
        if (scalar(connection, "SELECT IF(@@log_bin=0 OR @@log_bin_trust_function_creators=1,1,0)") != 1) {
            throw new IllegalStateException("STOP_TRIGGER_POLICY 请由数据库管理员配置触发器策略，应用不申请SUPER");
        }
        boolean triggerPrivilege = false;
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SHOW GRANTS")) {
            while (rows.next()) {
                String grant = rows.getString(1).toUpperCase(Locale.ROOT);
                String target = "`" + schema.toUpperCase(Locale.ROOT).replace("`", "``") + "`.*";
                if ((grant.contains(" ON *.* TO ") || grant.contains(" ON " + target + " TO "))
                        && (grant.startsWith("GRANT ALL PRIVILEGES ON ") || grant.matches("GRANT .*\\bTRIGGER\\b.* ON .*"))) {
                    triggerPrivilege = true;
                }
            }
        }
        if (!triggerPrivilege) throw new IllegalStateException("STOP_TRIGGER_PRIVILEGE 迁移账号须具备目标schema的TRIGGER权限");
    }

    private int scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
