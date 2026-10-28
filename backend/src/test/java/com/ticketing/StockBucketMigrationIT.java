package com.ticketing;

import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/** V10只有结构迁移；业务归属切换由真实离线工具另行验证，失败DDL保留不repair。 */
class StockBucketMigrationIT {
    JdbcTemplate db;
    String url;
    @BeforeEach void isolated() {
        assertThat(System.getenv("MYSQL_HOST")).isEqualTo("127.0.0.1");
        assertThat(System.getenv("MYSQL_PORT")).isEqualTo("13307");
        var root=jdbc("jdbc:mysql://127.0.0.1:13307/ticketing_perf");
        assertThat(root.queryForObject("SELECT token FROM performance_control.environment_guard",String.class)).isEqualTo(System.getenv("RECOVERY_TEST_GUARD"));
        String schema="ticketing_s17_"+UUID.randomUUID().toString().replace("-","")+"_it";
        root.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        url="jdbc:mysql://127.0.0.1:13307/"+schema+"?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        db=jdbc(url);
    }
    JdbcTemplate jdbc(String connection) {return new JdbcTemplate(new DriverManagerDataSource(connection,System.getenv("MYSQL_USER"),System.getenv("MYSQL_PASSWORD")));}
    Flyway migration(String target) {
        var config=Flyway.configure().dataSource(url,System.getenv("MYSQL_USER"),System.getenv("MYSQL_PASSWORD"))
                .locations("classpath:db/migration").cleanDisabled(true).placeholders(Map.of("quotaLimit","4"));
        // 本类验证 V9→V10 的历史结构边界，不能把后续 V11–V15 误计为 V10 变更。
        config.target(target == null ? "10" : target);return config.load();
    }
    void history() {
        migration("9").migrate();
        db.update("INSERT INTO user_account(id,username,password_hash) VALUES(1,'s17_history','隔离不可登录')");
        db.update("INSERT INTO activity(id,title,category,city,venue) VALUES(1,'历史分桶探针','CONCERT','上海','隔离')");
        db.update("INSERT INTO activity_session(id,activity_id,title,starts_at,ends_at,sale_starts_at) VALUES(1,1,'历史','2030-01-01','2030-01-02','2029-01-01')");
        db.update("INSERT INTO ticket_sku(id,session_id,name,price_cents,total_stock,locked_stock,sold_stock,sale_starts_at,sale_ends_at) VALUES(1,1,'历史票',100,10,2,1,'2029-01-01','2029-12-31')");
        db.update("""
                INSERT INTO ticket_order(id,order_no,user_id,session_id,status,total_amount_cents,idempotency_operation,idempotency_key,request_hash,created_at,expires_at,paid_at)
                VALUES(1,'HISTORY-PENDING',1,1,'PENDING',200,'CREATE_ORDER','p',UNHEX(SHA2('p',256)),'2029-01-01','2029-01-02',NULL),
                (2,'HISTORY-PAID',1,1,'PAID',100,'CREATE_ORDER','s',UNHEX(SHA2('s',256)),'2029-01-01','2029-01-02','2029-01-01')
                """);
        db.update("INSERT INTO ticket_order_item(order_id,sku_id,session_id,quantity,unit_price_cents,sku_name_snapshot) VALUES(1,1,1,2,100,'历史票'),(2,1,1,1,100,'历史票')");
    }
    java.util.List<java.util.Map<String,Object>> orderFacts() {
        return db.queryForList("""
                SELECT id,order_no,user_id,session_id,status,total_amount_cents,idempotency_operation,idempotency_key,
                       HEX(request_hash) request_hash,created_at,expires_at,paid_at,updated_at
                FROM ticket_order ORDER BY id
                """);
    }
    @Test void freshV10HasSingleDefaultsAndEnforcesBucketAndAllocationConstraints() {
        migration(null).migrate();migration(null).validate();
        assertThat(db.queryForObject("SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1",String.class)).isEqualTo("10");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ticket_stock_bucket",Integer.class)).isZero();
        assertThatThrownBy(()->db.update("INSERT INTO ticket_stock_bucket(sku_id,bucket_no,total_stock) VALUES(1,16,0)"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void v9PendingAndPaidFactsRemainUnchangedAndNeverAutomaticallyAcquireBuckets() {
        history();var orders=orderFacts();var items=db.queryForList("SELECT order_id,sku_id,session_id,quantity,unit_price_cents,sku_name_snapshot FROM ticket_order_item ORDER BY id");
        var stock=db.queryForMap("SELECT total_stock,locked_stock,sold_stock,version FROM ticket_sku WHERE id=1");
        assertThat(migration(null).migrate().migrationsExecuted).isEqualTo(1);migration(null).validate();
        assertThat(orderFacts()).isEqualTo(orders);
        assertThat(db.queryForList("SELECT order_id,sku_id,session_id,quantity,unit_price_cents,sku_name_snapshot FROM ticket_order_item ORDER BY id")).isEqualTo(items);
        assertThat(db.queryForMap("SELECT total_stock,locked_stock,sold_stock,version FROM ticket_sku WHERE id=1")).isEqualTo(stock);
        assertThat(db.queryForObject("SELECT inventory_strategy FROM ticket_sku WHERE id=1",String.class)).isEqualTo("SINGLE");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_stock_allocation",Integer.class)).isZero();
        assertThatThrownBy(()->db.update("UPDATE ticket_sku SET inventory_strategy='BUCKETED',bucket_count=4 WHERE id=1"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void ddlCollisionPreservesFailureHistoryAndOriginalInventoryWithoutAutomaticRepair() {
        history();db.execute("CREATE TABLE ticket_stock_bucket(collision INT)");
        assertThatThrownBy(()->migration(null).migrate()).isInstanceOf(org.flywaydb.core.api.FlywayException.class);
        assertThat(db.queryForObject("SELECT success FROM flyway_schema_history WHERE version='10'",Boolean.class)).isFalse();
        assertThat(db.queryForObject("SELECT locked_stock FROM ticket_sku WHERE id=1",Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ticket_order",Integer.class)).isEqualTo(2);
        assertThatThrownBy(()->migration(null).validate()).isInstanceOf(org.flywaydb.core.api.FlywayException.class);
    }

    @Test void missingCreateViewPrivilegePreservesPartialV10AndStopsRepeatedMigration() {
        migration("9").migrate();
        String schema = db.queryForObject("SELECT DATABASE()", String.class);
        db.execute("GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES,DROP,TRIGGER ON `"
                + schema + "`.* TO 'ticketing_perf'@'%'");
        var restricted = Flyway.configure().dataSource(url, "ticketing_perf", System.getenv("MIGRATION_TEST_PASSWORD"))
                .locations("classpath:db/migration").cleanDisabled(true).placeholders(Map.of("quotaLimit", "4")).load();
        assertThatThrownBy(restricted::migrate).isInstanceOf(org.flywaydb.core.api.FlywayException.class)
                .hasStackTraceContaining("1142");
        assertThat(db.queryForObject("SELECT success FROM flyway_schema_history WHERE version='10'", Boolean.class)).isFalse();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='ticket_stock_bucket'", Integer.class)).isEqualTo(1);
        assertThatThrownBy(restricted::migrate).isInstanceOf(org.flywaydb.core.api.FlywayException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='10'", Integer.class)).isEqualTo(1);
    }
}
