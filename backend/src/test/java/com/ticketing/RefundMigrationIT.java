package com.ticketing;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 独立验收MySQL内新建随机schema，不修改开发库；失败场景原样保留以便审计。 */
class RefundMigrationIT {
    @Test
    void historicalV5UpgradePreservesPaidFactsAndCompositeReferences() {
        Probe probe = v5("upgrade");
        JdbcTemplate db = probe.jdbc();
        var paymentBefore = db.queryForMap("SELECT * FROM payment_record WHERE id=1");
        var ticketsBefore = db.queryForList("SELECT * FROM electronic_ticket ORDER BY id");
        Flyway latest = migration(probe.url(), "6");
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        latest.validate();
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("6");
        assertThat(db.queryForMap("SELECT * FROM payment_record WHERE id=1")).isEqualTo(paymentBefore);
        assertThat(db.queryForList("SELECT * FROM electronic_ticket ORDER BY id"))
                .usingRecursiveComparison().isEqualTo(ticketsBefore);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE() AND constraint_name='fk_waitlist_order_sku'",Integer.class)).isEqualTo(1);
        db.update("UPDATE ticket_order SET status='REFUNDED' WHERE id=1");
        assertThat(db.queryForObject("SELECT paid_at FROM ticket_order WHERE id=1",String.class)).isNotNull();
        assertThatThrownBy(() -> db.update("UPDATE ticket_order SET paid_at=NULL WHERE id=1")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        db.update("INSERT INTO refund_record(order_id,refund_no,payment_id,amount_cents,reason) VALUES(1,'MIGRATION-REFUND',1,100,'SESSION_CANCELLED')");
        assertThatThrownBy(() -> db.update("INSERT INTO refund_record(order_id,refund_no,payment_id,amount_cents,reason) VALUES(1,'DUPLICATE',1,100,'SESSION_CANCELLED')"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void partialDdlFailurePreservesOriginalFactsAndFailedHistoryWithoutRepair() {
        Probe probe = v5("failure");
        JdbcTemplate db = probe.jdbc();
        db.execute("CREATE TABLE refund_record(marker VARCHAR(32) PRIMARY KEY)");
        db.update("INSERT INTO refund_record VALUES('保留失败现场')");
        assertThatThrownBy(() -> migration(probe.url(), null).migrate()).isInstanceOf(FlywayException.class);
        assertThat(db.queryForObject("SELECT success FROM flyway_schema_history WHERE version='6'",Boolean.class)).isFalse();
        assertThat(db.queryForObject("SELECT marker FROM refund_record",String.class)).isEqualTo("保留失败现场");
        assertThat(db.queryForObject("SELECT status FROM ticket_order WHERE id=1",String.class)).isEqualTo("PAID");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM payment_record WHERE order_id=1 AND status='SUCCEEDED'",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM electronic_ticket WHERE order_id=1 AND status='UNUSED'",Integer.class)).isEqualTo(1);
        // MySQL已提交前一条ALTER，不能把整个迁移文件宣称为已回滚。
        assertThat(db.queryForObject("SELECT CHECK_CLAUSE FROM information_schema.check_constraints WHERE constraint_schema=DATABASE() AND constraint_name='ck_order_status'",String.class)).contains("REFUNDED");
        assertThatThrownBy(() -> migration(probe.url(), null).validate()).isInstanceOf(FlywayException.class);
    }

    private Probe v5(String purpose) {
        String configured = System.getenv("MYSQL_DATABASE");
        assumeTrue(configured != null && configured.endsWith("_it"), "历史升级探针仅在显式隔离验收库执行");
        String base = "jdbc:mysql://" + System.getenv().getOrDefault("MYSQL_HOST","127.0.0.1") + ":"
                + System.getenv().getOrDefault("MYSQL_PORT","3307") + "/";
        String schema = "ticketing_stage10_" + purpose + "_" + UUID.randomUUID().toString().replace("-","");
        String parameters = "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8";
        JdbcTemplate control = jdbc(base + configured + parameters);
        control.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        String url = base + schema + parameters;
        migration(url,"5").migrate();
        JdbcTemplate db = jdbc(url);
        db.update("INSERT INTO user_account(id,username,password_hash) VALUES(1,'migration_user','不可登录占位')");
        db.update("INSERT INTO activity(id,title,category,city,venue) VALUES(1,'历史活动','CONCERT','上海','验收场馆')");
        db.update("INSERT INTO activity_session(id,activity_id,title,starts_at,ends_at,sale_starts_at) VALUES(1,1,'历史场次','2030-01-01 10:00:00','2030-01-01 12:00:00','2029-01-01')");
        db.update("INSERT INTO ticket_sku(id,session_id,name,price_cents,total_stock,sold_stock,sale_starts_at,sale_ends_at) VALUES(1,1,'历史票档',100,10,1,'2029-01-01','2029-12-31')");
        db.update("INSERT INTO ticket_order(id,order_no,user_id,session_id,status,total_amount_cents,idempotency_operation,idempotency_key,request_hash,expires_at,paid_at) VALUES(1,'HISTORY',1,1,'PAID',100,'CREATE_ORDER','historical',UNHEX(SHA2('historical',256)),'2030-01-01',UTC_TIMESTAMP(6))");
        db.update("INSERT INTO ticket_order_item(id,order_id,sku_id,session_id,quantity,unit_price_cents,sku_name_snapshot) VALUES(1,1,1,1,1,100,'历史票档')");
        db.update("INSERT INTO payment_record(id,payment_no,order_id,amount_cents,status,paid_at) SELECT 1,'HISTORY-PAY',id,total_amount_cents,'SUCCEEDED',paid_at FROM ticket_order WHERE id=1");
        db.update("INSERT INTO electronic_ticket(order_id,order_item_id,sku_id,owner_id,ticket_no,sequence_no,credential_digest,credential_key_version) VALUES(1,1,1,1,'MIGRATION-NOT-A-VALID-CREDENTIAL',1,UNHEX(SHA2('migration',256)),1)");
        return new Probe(url,db);
    }

    private Flyway migration(String url,String target) {
        var configuration = Flyway.configure().dataSource(url,System.getenv("MYSQL_USER"),System.getenv("MYSQL_PASSWORD"))
                .locations("classpath:db/migration").cleanDisabled(true);
        if (target != null) configuration.target(target);
        return configuration.load();
    }
    private JdbcTemplate jdbc(String url) {
        return new JdbcTemplate(new DriverManagerDataSource(url,System.getenv("MYSQL_USER"),System.getenv("MYSQL_PASSWORD")));
    }
    private record Probe(String url,JdbcTemplate jdbc) {}
}
