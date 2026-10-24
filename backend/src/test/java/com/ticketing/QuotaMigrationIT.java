package com.ticketing;

import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/** V8真实历史与异常升级；只在固定隔离实例+随机guard内新建schema，绝不clean或repair。 */
class QuotaMigrationIT {
    JdbcTemplate root,db;
    String url;
    @BeforeEach void isolated() {
        assertThat(System.getenv("MYSQL_HOST")).isEqualTo("127.0.0.1");
        assertThat(System.getenv("MYSQL_PORT")).isEqualTo("13307");
        String base="jdbc:mysql://127.0.0.1:13307/";
        root=jdbc(base+"ticketing_perf");
        assertThat(root.queryForObject("SELECT token FROM performance_control.environment_guard",String.class)).isEqualTo(System.getenv("RECOVERY_TEST_GUARD"));
        String schema="ticketing_s16_migration_"+UUID.randomUUID().toString().replace("-","")+"_it";
        root.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        url=base+schema+"?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";db=jdbc(url);
    }
    JdbcTemplate jdbc(String connection){return new JdbcTemplate(new DriverManagerDataSource(connection,System.getenv("MYSQL_USER"),System.getenv("MYSQL_PASSWORD")));}
    Flyway migration(String target,int limit) {
        var config=Flyway.configure().dataSource(url,System.getenv("MYSQL_USER"),System.getenv("MYSQL_PASSWORD"))
                .locations("classpath:db/migration").cleanDisabled(true).placeholders(Map.of("quotaLimit",Integer.toString(limit)));
        if(target!=null)config.target(target);return config.load();
    }
    void history() {
        migration("8",4).migrate();
        db.update("INSERT INTO user_account(id,username,password_hash) VALUES(1,'quota_history','隔离不可登录'),(2,'quota_waiting','隔离不可登录')");
        db.update("INSERT INTO activity(id,title,category,city,venue) VALUES(1,'历史额度','CONCERT','上海','隔离')");
        for(int id=1;id<=2;id++) db.update("INSERT INTO activity_session(id,activity_id,title,starts_at,ends_at,sale_starts_at) VALUES(?,1,'历史场次','2030-01-01 10:00:00','2030-01-01 12:00:00','2029-01-01')",id);
        for(int id=1;id<=3;id++) db.update("INSERT INTO ticket_sku(id,session_id,name,price_cents,total_stock,sale_starts_at,sale_ends_at) VALUES(?,?,?,100,100,'2029-01-01','2029-12-31')",id,id==3?2:1,"历史票档"+id);
    }
    void order(int id,int ticket,int quantity,String status,String operation) {
        int session=ticket==3?2:1;
        db.update("INSERT INTO ticket_order(id,order_no,user_id,session_id,status,total_amount_cents,idempotency_operation,idempotency_key,request_hash,created_at,expires_at,paid_at) VALUES(?,?,1,?,?,?,?,?,UNHEX(SHA2('history',256)),'2029-01-01','2030-01-01',?)",
                id,"HISTORY-"+id,session,status,(long)quantity*100,operation,"key-"+id,Set.of("PAID","REFUNDED").contains(status)?"2029-01-02":null);
        db.update("INSERT INTO ticket_order_item(id,order_id,sku_id,session_id,quantity,unit_price_cents,sku_name_snapshot) VALUES(?,?,?,?,?,100,'历史快照')",id,id,ticket,session,quantity);
    }
    @Test void freshDatabaseAndEmptyV8UpgradeHaveNoArtificialOccupiedQuota() {
        var latest=migration(null,4);latest.migrate();latest.validate();
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("9");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM user_session_quota",Integer.class)).isZero();
        migration(null,3).validate(); // 调整运行限额不会改写已应用版本或迫使repair。
        assertThatThrownBy(()->db.update("INSERT INTO user_session_quota VALUES(1,1,-1,UTC_TIMESTAMP(6))")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void v8HistoryAcrossTicketsSessionsRefundsAndWaitlistBackfillsExactlyAndPreservesEveryFact() {
        history();order(1,1,1,"PENDING","CREATE_ORDER");order(2,2,2,"PAID","CREATE_ORDER");
        order(3,1,4,"CANCELLED","CREATE_ORDER");order(4,2,1,"EXPIRED","CREATE_ORDER");
        order(5,1,3,"REFUNDED","CREATE_ORDER");order(6,3,1,"PENDING","WAITLIST_CONFIRM");
        db.update("INSERT INTO waitlist_entry(user_id,sku_id,quantity,status,reserved_order_id,created_at,expires_at) VALUES(1,3,1,'ALLOCATED',6,'2029-01-01','2030-01-01'),(2,2,1,'WAITING',NULL,'2029-01-01','2030-01-01')");
        db.update("INSERT INTO payment_record(id,payment_no,order_id,amount_cents,status,paid_at) SELECT id,CONCAT('PAY-',id),id,total_amount_cents,'SUCCEEDED',paid_at FROM ticket_order WHERE status IN('PAID','REFUNDED')");
        db.update("INSERT INTO refund_record(order_id,refund_no,payment_id,amount_cents,reason) VALUES(5,'HISTORY-REFUND',5,300,'CUSTOMER_REQUEST')");
        var before=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(String table:List.of("ticket_order","ticket_order_item","payment_record","refund_record","waitlist_entry","purchase_request","outbox_event","admin_audit_log","flyway_schema_history"))
            before.put(table,db.queryForList("SELECT * FROM "+table+" ORDER BY 1"));
        var latest=migration(null,4);assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);latest.validate();
        for(var entry:before.entrySet()) {
            String suffix=entry.getKey().equals("flyway_schema_history")?" WHERE version<>'9'":"";
            assertThat(db.queryForList("SELECT * FROM "+entry.getKey()+suffix+" ORDER BY 1")).usingRecursiveComparison().isEqualTo(entry.getValue());
        }
        assertThat(db.queryForObject("SELECT occupied_quantity FROM user_session_quota WHERE user_id=1 AND session_id=1",Integer.class)).isEqualTo(3);
        assertThat(db.queryForObject("SELECT occupied_quantity FROM user_session_quota WHERE user_id=1 AND session_id=2",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM user_session_quota",Integer.class)).isEqualTo(2);
    }
    @Test void missingHistoricalItemRejectsMigrationAndPreservesFailureHistoryWithoutRepair() {
        history();order(1,1,1,"PENDING","CREATE_ORDER");db.update("DELETE FROM ticket_order_item WHERE id=1");reject(4);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ticket_order",Integer.class)).isEqualTo(1);
    }
    @Test void historicalOverLimitUsesConfiguredLimitAndNeverClipsToMaximum() {
        history();order(1,1,2,"PENDING","CREATE_ORDER");order(2,2,2,"PAID","CREATE_ORDER");reject(3);
        assertThat(db.queryForObject("SELECT SUM(quantity) FROM ticket_order_item",Long.class)).isEqualTo(4);
    }
    @Test void historicalIntegerOverflowRejectsBeforeInsertingAnyAggregate() {
        history();order(1,1,Integer.MAX_VALUE,"PENDING","CREATE_ORDER");order(2,2,1,"PENDING","CREATE_ORDER");reject(Integer.MAX_VALUE);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM user_session_quota",Integer.class)).isZero();
    }
    @Test void orphanAndCrossSessionFactsAreReportedRatherThanCorrected() throws Exception {
        history();order(1,1,1,"PENDING","CREATE_ORDER");
        try(var connection=Objects.requireNonNull(db.getDataSource()).getConnection();var statement=connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS=0");statement.executeUpdate("UPDATE ticket_order_item SET session_id=2,sku_id=999 WHERE id=1");statement.execute("SET FOREIGN_KEY_CHECKS=1");
        }
        reject(4);assertThat(db.queryForObject("SELECT sku_id FROM ticket_order_item WHERE id=1",Long.class)).isEqualTo(999);
    }
    void reject(int limit) {
        assertThatThrownBy(()->migration(null,limit).migrate()).isInstanceOf(org.flywaydb.core.api.FlywayException.class)
                .hasStackTraceContaining("ck_user_session_quota_nonnegative");
        assertThat(db.queryForObject("SELECT success FROM flyway_schema_history WHERE version='9'",Boolean.class)).isFalse();
        assertThatThrownBy(()->migration(null,limit).validate()).isInstanceOf(org.flywaydb.core.api.FlywayException.class);
    }
}
