package com.ticketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ticketing.config.MigrationSafetyConfig;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

/** 历史库存事件序号和分桶订单只在随机隔离库中验证迁移。 */
class InventorySyncMigrationIT {
    private JdbcTemplate db;
    private String url;

    @BeforeEach
    void fixture() {
        assumeTrue("127.0.0.1".equals(System.getenv("MYSQL_HOST"))
                && "13307".equals(System.getenv("MYSQL_PORT"))
                && System.getenv("MYSQL_DATABASE").endsWith("_it")
                && System.getenv("RECOVERY_TEST_GUARD") != null);
        String base="jdbc:mysql://127.0.0.1:13307/";
        var root=new JdbcTemplate(new DriverManagerDataSource(base+"ticketing_perf",
                System.getenv("MYSQL_USER"),System.getenv("MYSQL_PASSWORD")));
        assertThat(root.queryForObject("SELECT token FROM performance_control.environment_guard",String.class))
                .isEqualTo(System.getenv("RECOVERY_TEST_GUARD"));
        String schema="ticketing_inventory_migration_"+UUID.randomUUID().toString().replace("-","").substring(0,16)+"_it";
        root.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        url=base+schema+"?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        db=new JdbcTemplate(new DriverManagerDataSource(url,System.getenv("MYSQL_USER"),System.getenv("MYSQL_PASSWORD")));
        migration("15").migrate();
        db.update("INSERT INTO user_account(id,username,password_hash) VALUES(1,'inventory_history','不可登录')");
        db.update("INSERT INTO activity(id,title,category,city,venue) VALUES(1,'历史','CONCERT','上海','隔离')");
        db.update("INSERT INTO activity_session(id,activity_id,title,starts_at,ends_at,sale_starts_at) VALUES(1,1,'历史','2030-01-01 10:00:00','2030-01-01 12:00:00','2029-01-01')");
        db.update("INSERT INTO ticket_sku(id,session_id,name,price_cents,total_stock,sale_starts_at,sale_ends_at,inventory_strategy,bucket_count,redis_inventory_initialized) VALUES(1,1,'旧分桶',100,4,'2029-01-01','2029-12-31','BUCKETED',4,TRUE)");
        for(int bucket=0;bucket<4;bucket++) db.update("INSERT INTO ticket_stock_bucket(sku_id,bucket_no,total_stock,locked_stock) VALUES(1,?,1,?)",bucket,bucket==0?1:0);
        db.update("INSERT INTO ticket_order(id,order_no,user_id,session_id,total_amount_cents,idempotency_operation,idempotency_key,request_hash,created_at,expires_at) VALUES(1,'HISTORY',1,1,100,'CREATE_ORDER','history',UNHEX(SHA2('history',256)),'2029-01-01','2030-01-01')");
        db.update("INSERT INTO ticket_order_item(order_id,sku_id,session_id,quantity,unit_price_cents,sku_name_snapshot) VALUES(1,1,1,1,100,'旧分桶')");
        db.update("INSERT INTO order_stock_allocation(order_id,sku_id,bucket_no,quantity) VALUES(1,1,0,1)");
    }

    private Flyway migration(String target) {
        var configuration=Flyway.configure().dataSource(url,System.getenv("MYSQL_USER"),System.getenv("MYSQL_PASSWORD"))
                .locations("classpath:db/migration").cleanDisabled(true).placeholders(Map.of("quotaLimit","4"));
        if(target!=null) configuration.target(target);
        return configuration.load();
    }

    @Test
    void sentLegacyVersionAndHistoricalBucketAllocationSurviveMigration() {
        db.update("INSERT INTO outbox_event(id,event_id,aggregate_type,aggregate_id,event_type,payload,status,sent_at) VALUES(40,'legacy-40','TICKET_SKU',1,'WAITLIST_DISPATCH_REQUESTED','{}','SENT',UTC_TIMESTAMP(6))");
        var order=db.queryForMap("SELECT * FROM ticket_order WHERE id=1");
        var allocation=db.queryForList("SELECT * FROM order_stock_allocation WHERE order_id=1");
        assertThat(migration(null).migrate().migrationsExecuted).isEqualTo(1);
        assertThat(db.queryForObject("SELECT inventory_version FROM inventory_sync_version WHERE sku_id=1",Long.class)).isEqualTo(41);
        assertThat(db.queryForMap("SELECT * FROM ticket_order WHERE id=1")).isEqualTo(order);
        assertThat(db.queryForList("SELECT * FROM order_stock_allocation WHERE order_id=1")).isEqualTo(allocation);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='outbox_event'",Integer.class)).isZero();
    }

    @Test
    void existingSkuAndPendingLegacyEventsBlockBeforeMigrationDdl() {
        db.update("INSERT INTO outbox_event(event_id,aggregate_type,aggregate_id,event_type,payload,status) VALUES('pending','TICKET_SKU',1,'WAITLIST_DISPATCH_REQUESTED','{}','PENDING')");
        var safety=new MigrationSafetyConfig();
        org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy strategy =
                ReflectionTestUtils.invokeMethod(safety,"migrationSafetyStrategy");
        assertThatThrownBy(()->strategy.migrate(migration(null)))
                .hasMessageContaining("STOP_INVENTORY_SWITCH");
        ReflectionTestUtils.setField(safety,"inventoryMigrationApproved",true);
        assertThatThrownBy(()->strategy.migrate(migration(null)))
                .hasMessageContaining("STOP_INVENTORY_OUTBOX");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='inventory_sync_version'",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE status='PENDING'",Integer.class)).isEqualTo(1);
    }
}
