package com.ticketing;

import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 只在固定专用实例和随机标记验证空库与带真实历史订单的V7增量升级。 */
class AsyncPurchaseMigrationIT {
    JdbcTemplate root,db;
    String url;
    @BeforeEach void isolated() {
        assumeTrue("13307".equals(System.getenv("MYSQL_PORT")) && "127.0.0.1".equals(System.getenv("MYSQL_HOST"))
                && System.getenv("MYSQL_DATABASE").endsWith("_it") && System.getenv("RECOVERY_TEST_GUARD")!=null);
        String prefix="jdbc:mysql://127.0.0.1:13307/";
        root=new JdbcTemplate(new DriverManagerDataSource(prefix+"ticketing_perf",System.getenv("MYSQL_USER"),System.getenv("MYSQL_PASSWORD")));
        assertThat(root.queryForObject("SELECT token FROM performance_control.environment_guard",String.class)).isEqualTo(System.getenv("RECOVERY_TEST_GUARD"));
        String schema="ticketing_s14_migration_"+UUID.randomUUID().toString().replace("-","")+"_it";
        root.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        url=prefix+schema+"?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        db=new JdbcTemplate(new DriverManagerDataSource(url,System.getenv("MYSQL_USER"),System.getenv("MYSQL_PASSWORD")));
    }
    Flyway migration(String target) {
        var configuration=Flyway.configure().dataSource(url,System.getenv("MYSQL_USER"),System.getenv("MYSQL_PASSWORD"))
                .locations("classpath:db/migration").cleanDisabled(true);
        if(target!=null)configuration.target(target);
        return configuration.load();
    }
    @Test void freshDatabaseHasConsistentEmptyBudgetAndRequestConstraints() {
        var migration=migration(null);migration.migrate();migration.validate();
        assertThat(migration.info().current().getVersion().getVersion()).isEqualTo("8");
        assertThat(db.queryForObject("SELECT pending_count FROM purchase_capacity WHERE id=1",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_request",Integer.class)).isZero();
        assertThatThrownBy(()->db.update("UPDATE purchase_capacity SET pending_count=-1 WHERE id=1")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(db.queryForList("SELECT column_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='purchase_request' AND index_name='uk_purchase_idempotency' ORDER BY seq_in_index",String.class)).containsExactly("user_id","idempotency_operation","idempotency_key");
    }
    @Test void v7UpgradePreservesAllHistoricalFactsAndRegistersOriginalIdempotency() {
        migration("7").migrate();
        db.update("INSERT INTO user_account(id,username,password_hash) VALUES(1,'migration_buyer','不可登录的占位值')");
        db.update("INSERT INTO activity(id,title,category,city,venue,status,published_at) VALUES(1,'历史演示','CONCERT','上海','迁移验收','PUBLISHED',UTC_TIMESTAMP(6))");
        db.update("INSERT INTO activity_session(id,activity_id,title,starts_at,ends_at,sale_starts_at) VALUES(1,1,'历史场次',TIMESTAMPADD(DAY,1,UTC_TIMESTAMP(6)),TIMESTAMPADD(DAY,2,UTC_TIMESTAMP(6)),TIMESTAMPADD(DAY,-1,UTC_TIMESTAMP(6)))");
        db.update("INSERT INTO ticket_sku(id,session_id,name,price_cents,total_stock,locked_stock,sale_starts_at,sale_ends_at,status) VALUES(1,1,'历史票档',12345,20,2,TIMESTAMPADD(HOUR,-1,UTC_TIMESTAMP(6)),TIMESTAMPADD(HOUR,1,UTC_TIMESTAMP(6)),'ON_SALE')");
        for(int id=1;id<=2;id++) {
            db.update("INSERT INTO ticket_order(id,order_no,user_id,session_id,status,total_amount_cents,idempotency_operation,idempotency_key,request_hash,expires_at) VALUES(?,?,1,1,?,24690,'CREATE_ORDER',?,UNHEX(SHA2('1:2',256)),TIMESTAMPADD(MINUTE,15,UTC_TIMESTAMP(6)))",id,"HISTORY-"+id,id==1?"PENDING":"CANCELLED","old-key-"+id);
            db.update("INSERT INTO ticket_order_item(order_id,sku_id,session_id,quantity,unit_price_cents,sku_name_snapshot) VALUES(?,1,1,2,12345,'历史票档')",id);
        }
        List<String> tables=List.of("user_account","activity","activity_session","ticket_sku","ticket_order","ticket_order_item","payment_record","waitlist_entry","electronic_ticket","refund_record","order_aftercare","staff_session_permission","admin_audit_log","outbox_event");
        Map<String,List<Map<String,Object>>> before=new HashMap<>();
        for(String table:tables)before.put(table,db.queryForList("SELECT * FROM "+table+" ORDER BY 1"));
        var history=db.queryForList("SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank");
        var upgrade=migration(null);assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);upgrade.validate();
        for(String table:tables)assertThat(db.queryForList("SELECT * FROM "+table+" ORDER BY 1")).as(table).usingRecursiveComparison().isEqualTo(before.get(table));
        assertThat(db.queryForList("SELECT version,checksum,success FROM flyway_schema_history WHERE version<>'8' ORDER BY installed_rank")).isEqualTo(history);
        assertThat(db.queryForList("SELECT order_id FROM purchase_request WHERE status='SUCCEEDED' ORDER BY order_id",Long.class)).containsExactly(1L,2L);
        assertThat(db.queryForObject("SELECT pending_count FROM purchase_capacity WHERE id=1",Integer.class)).isZero();
        assertThatThrownBy(()->db.update("UPDATE purchase_request SET status='FAILED',failure_code='PURCHASE_LIMIT' WHERE order_id=1")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE()",Integer.class)).isEqualTo(2);
    }
}
