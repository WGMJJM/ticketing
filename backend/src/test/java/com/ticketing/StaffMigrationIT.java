package com.ticketing;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

/** V7空库由DatabaseMigrationIT覆盖；本用例独立从V6升级并保留历史核销、角色和停用状态。 */
class StaffMigrationIT {
    @Test
    void v6UpgradePreservesRolesDisabledAccountsAndHistoricalCheckinFacts() {
        String configured = System.getenv("MYSQL_DATABASE");
        assumeTrue(configured != null && configured.endsWith("_it"), "迁移探针仅在显式独立验收环境执行");
        String base =
                "jdbc:mysql://"
                        + System.getenv().getOrDefault("MYSQL_HOST", "127.0.0.1")
                        + ":"
                        + System.getenv().getOrDefault("MYSQL_PORT", "3307")
                        + "/";
        String parameters =
                "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8";
        String schema =
                "ticketing_stage11_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        jdbc(base + configured + parameters)
                .execute(
                        "CREATE DATABASE "
                                + schema
                                + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        String url = base + schema + parameters;
        migration(url, "6").migrate();
        var db = jdbc(url);
        db.update(
                "INSERT INTO user_account(id,username,password_hash,role,status) VALUES(1,'historical_admin','不可登录占位','ADMIN','ACTIVE'),(2,'disabled_user','不可登录占位','USER','DISABLED'),(3,'historical_buyer','不可登录占位','USER','ACTIVE')");
        db.update(
                "INSERT INTO activity(id,title,category,city,venue) VALUES(1,'历史活动','CONCERT','上海','验收场馆')");
        db.update(
                "INSERT INTO activity_session(id,activity_id,title,starts_at,ends_at,sale_starts_at) VALUES(1,1,'历史场次','2030-01-01 10:00:00','2030-01-01 12:00:00','2029-01-01')");
        db.update(
                "INSERT INTO ticket_sku(id,session_id,name,price_cents,total_stock,sold_stock,sale_starts_at,sale_ends_at) VALUES(1,1,'历史票档',100,10,1,'2029-01-01','2029-12-31')");
        db.update(
                "INSERT INTO ticket_order(id,order_no,user_id,session_id,status,total_amount_cents,idempotency_operation,idempotency_key,request_hash,expires_at,paid_at) VALUES(1,'HISTORY',3,1,'PAID',100,'CREATE_ORDER','historical',UNHEX(SHA2('historical',256)),'2030-01-01',UTC_TIMESTAMP(6))");
        db.update(
                "INSERT INTO ticket_order_item(id,order_id,sku_id,session_id,quantity,unit_price_cents,sku_name_snapshot) VALUES(1,1,1,1,1,100,'历史票档')");
        db.update(
                "INSERT INTO payment_record(id,payment_no,order_id,amount_cents,status,paid_at) SELECT 1,'HISTORY-PAY',id,total_amount_cents,'SUCCEEDED',paid_at FROM ticket_order WHERE id=1");
        db.update(
                "INSERT INTO electronic_ticket(order_id,order_item_id,sku_id,owner_id,ticket_no,sequence_no,credential_digest,credential_key_version,status,used_at,checked_by) VALUES(1,1,1,3,'NOT-A-VALID-CREDENTIAL',1,UNHEX(SHA2('historical',256)),1,'USED',UTC_TIMESTAMP(6),1)");
        var roles = db.queryForList("SELECT id,role,status FROM user_account ORDER BY id");
        var tickets = db.queryForList("SELECT * FROM electronic_ticket ORDER BY id");
        var payments = db.queryForList("SELECT * FROM payment_record ORDER BY id");
        var flyway = migration(url, "7");
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("7");
        assertThat(db.queryForList("SELECT id,role,status FROM user_account ORDER BY id"))
                .isEqualTo(roles);
        assertThat(db.queryForList("SELECT * FROM electronic_ticket ORDER BY id"))
                .usingRecursiveComparison()
                .isEqualTo(tickets);
        assertThat(db.queryForList("SELECT * FROM payment_record ORDER BY id"))
                .usingRecursiveComparison()
                .isEqualTo(payments);
        db.update("UPDATE user_account SET role='CHECKIN_STAFF' WHERE id=3");
        db.update(
                "INSERT INTO staff_session_permission(staff_id,session_id,granted_by) VALUES(3,1,1)");
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "INSERT INTO staff_session_permission(staff_id,session_id,granted_by) VALUES(3,1,1)"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "INSERT INTO staff_session_permission(staff_id,session_id,granted_by) VALUES(999,1,1)"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.update("UPDATE user_account SET role='ROOT' WHERE id=3"))
                .isInstanceOf(DataAccessException.class);
        db.update(
                "INSERT INTO admin_audit_log(operator_id,action,resource_type,resource_id,summary,before_values,after_values,request_id) VALUES(1,'SESSION_GRANT','STAFF',3,'历史升级验收',JSON_OBJECT(),JSON_OBJECT('sessionId','1'),?)",
                UUID.randomUUID().toString());
        assertThatThrownBy(() -> db.update("UPDATE admin_audit_log SET summary='篡改'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.update("DELETE FROM admin_audit_log"))
                .isInstanceOf(DataAccessException.class);
        db.update("UPDATE user_account SET status='DISABLED' WHERE id=1");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM admin_audit_log WHERE operator_id=1",
                                Integer.class))
                .isEqualTo(1);
    }

    private Flyway migration(String url, String target) {
        var config =
                Flyway.configure()
                        .dataSource(
                                url, System.getenv("MYSQL_USER"), System.getenv("MYSQL_PASSWORD"))
                        .locations("classpath:db/migration")
                        .cleanDisabled(true);
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private JdbcTemplate jdbc(String url) {
        return new JdbcTemplate(
                new DriverManagerDataSource(
                        url, System.getenv("MYSQL_USER"), System.getenv("MYSQL_PASSWORD")));
    }
}
