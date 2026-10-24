package com.ticketing;

import java.util.List;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 仅通过 mysql-it Maven profile 执行；连接真实 MySQL，每个测试事务结束后回滚。 */
@SpringBootTest
@Transactional
class DatabaseMigrationIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    @BeforeEach
    void prepareRelations() {
        jdbc.update("INSERT INTO user_account (id, username, password_hash) VALUES (900001, '集成测试用户', '不可登录的测试占位值')");
        jdbc.update("INSERT INTO activity (id, title, category, city, venue) VALUES (900001, '集成测试活动', 'CONCERT', '上海', '测试场馆')");
        jdbc.update("INSERT INTO activity_session (id, activity_id, title, starts_at, ends_at, sale_starts_at) VALUES (900001, 900001, '测试场次', '2030-01-01 10:00:00', '2030-01-01 12:00:00', '2029-12-01')");
        jdbc.update("INSERT INTO ticket_sku (id, session_id, name, price_cents, total_stock, sale_starts_at, sale_ends_at) VALUES (900001, 900001, '测试票档', 29900, 10, '2029-01-01', '2029-12-31')");
    }

    @Test
    void flywayCreatesDomainAndAftercareTablesWithChecksAndIndexes() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("10");
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='ticket_order' AND index_name='idx_order_user_session_status' ORDER BY seq_in_index", String.class))
                .containsExactly("user_id", "session_id", "status", "id");
        flyway.validate();
        var tables = jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()", String.class);
        assertThat(tables).containsAll(List.of("user_account", "activity", "activity_session", "ticket_sku", "ticket_order", "ticket_order_item", "payment_record", "waitlist_entry", "electronic_ticket", "outbox_event", "refund_record", "order_aftercare", "staff_session_permission", "admin_audit_log"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND constraint_type = 'CHECK'", Integer.class)).isGreaterThanOrEqualTo(25);
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'ticket_order' AND index_name = 'uk_order_idempotency' ORDER BY seq_in_index", String.class))
                .containsExactly("user_id", "idempotency_operation", "idempotency_key");
        assertThat(jdbc.queryForObject("SELECT @@session.time_zone", String.class)).isEqualTo("+00:00");
    }

    @Test
    void databaseEnforcesStockInvariantAndNonnegativeMoney() {
        assertRejected("UPDATE ticket_sku SET locked_stock = 8, sold_stock = 3 WHERE id = 900001", 3819);
        assertRejected("UPDATE ticket_sku SET locked_stock = -1 WHERE id = 900001", 3819);
        assertRejected("UPDATE ticket_sku SET sold_stock = -1 WHERE id = 900001", 3819);
        assertRejected("UPDATE ticket_sku SET total_stock = -1 WHERE id = 900001", 3819);
        assertRejected("UPDATE ticket_sku SET price_cents = -1 WHERE id = 900001", 3819);
        assertRejected("UPDATE ticket_sku SET version = -1 WHERE id = 900001", 3819);
        assertThat(jdbc.update("UPDATE ticket_sku SET locked_stock = 7, sold_stock = 3 WHERE id = 900001")).isEqualTo(1);
    }

    @Test
    void idempotencyIsUniquePerUserAndOperation() {
        insertOrder(900001, "TEST-ORDER-1", "CREATE_ORDER");
        assertThatThrownBy(() -> insertOrder(900002, "TEST-ORDER-2", "CREATE_ORDER"))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertOrder(900003, "TEST-ORDER-3", "WAITLIST_CONFIRM");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order WHERE user_id = 900001", Integer.class)).isEqualTo(2);
    }

    @Test
    void databaseRejectsInvalidReferencesAndStates() {
        assertRejected("UPDATE ticket_sku SET session_id = 999999 WHERE id = 900001", 1452);
        assertRejected("UPDATE user_account SET role = 'ROOT' WHERE id = 900001", 3819);
        insertOrder(900001, "TEST-ORDER-1", "CREATE_ORDER");
        assertRejected("UPDATE ticket_order SET status = 'UNKNOWN' WHERE id = 900001", 3819);
        assertRejected("UPDATE ticket_order SET status = 'PAID' WHERE id = 900001", 3819);
        jdbc.update("INSERT INTO ticket_order_item (order_id, sku_id, session_id, quantity, unit_price_cents, sku_name_snapshot) VALUES (900001, 900001, 900001, 1, 29900, '测试票档')");
        assertRejected("INSERT INTO ticket_order_item (order_id, sku_id, session_id, quantity, unit_price_cents, sku_name_snapshot) VALUES (900001, 900001, 900001, 1, 29900, '重复票档')", 1062);
    }

    @Test
    void activeWaitlistIsUniqueButHistoryIsRetained() {
        String insert = "INSERT INTO waitlist_entry (user_id, sku_id, quantity, expires_at) VALUES (900001, 900001, 1, '2030-01-01')";
        jdbc.update(insert);
        assertRejected(insert, 1062);
        jdbc.update("UPDATE waitlist_entry SET status = 'CANCELLED' WHERE user_id = 900001");
        jdbc.update(insert);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM waitlist_entry WHERE user_id = 900001", Integer.class)).isEqualTo(2);
    }

    @Test
    void reservedWaitlistMustReferenceTheExactOrderSku() {
        insertOrder(900001, "REFERENCE-ORDER", "WAITLIST_CONFIRM");
        jdbc.update("INSERT INTO ticket_order_item(order_id,sku_id,session_id,quantity,unit_price_cents,sku_name_snapshot) VALUES(900001,900001,900001,1,29900,'原票档')");
        jdbc.update("INSERT INTO ticket_sku(id,session_id,name,price_cents,total_stock,sale_starts_at,sale_ends_at) VALUES(900002,900001,'其他票档',29900,10,'2029-01-01','2029-12-31')");
        assertRejected("INSERT INTO waitlist_entry(user_id,sku_id,quantity,status,reserved_order_id,expires_at) VALUES(900001,900002,1,'ALLOCATED',900001,'2030-01-01')", 1452);
        jdbc.update("INSERT INTO waitlist_entry(user_id,sku_id,quantity,status,reserved_order_id,expires_at) VALUES(900001,900001,1,'ALLOCATED',900001,'2030-01-01')");
        assertRejected("UPDATE waitlist_entry SET sku_id=900002 WHERE reserved_order_id=900001", 1452);
        assertRejected("UPDATE ticket_order_item SET sku_id=900002 WHERE order_id=900001", 1451);
        assertThat(jdbc.queryForObject("SELECT sku_id FROM waitlist_entry WHERE reserved_order_id=900001", Long.class)).isEqualTo(900001L);
    }

    @Test
    void compositeReferenceIndexesAndExistingOwnerConstraintRemainEffective() {
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='ticket_order_item' AND index_name='uk_item_order_sku' ORDER BY seq_in_index", String.class))
                .containsExactly("order_id", "sku_id");
        assertThat(jdbc.queryForObject("SELECT MIN(non_unique) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='ticket_order_item' AND index_name='uk_item_order_sku'", Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.key_column_usage WHERE constraint_schema=DATABASE() AND constraint_name='fk_waitlist_order_sku' ORDER BY ordinal_position", String.class))
                .containsExactly("reserved_order_id", "sku_id");
        insertOrder(900001, "OWNER-ORDER", "WAITLIST_CONFIRM");
        jdbc.update("INSERT INTO ticket_order_item(order_id,sku_id,session_id,quantity,unit_price_cents,sku_name_snapshot) VALUES(900001,900001,900001,1,29900,'原票档')");
        jdbc.update("INSERT INTO user_account(id,username,password_hash) VALUES(900002,'另一集成用户','不可登录占位值')");
        assertRejected("INSERT INTO waitlist_entry(user_id,sku_id,quantity,status,reserved_order_id,expires_at) VALUES(900002,900001,1,'ALLOCATED',900001,'2030-01-01')", 1452);
    }

    private void insertOrder(long id, String orderNo, String operation) {
        jdbc.update("INSERT INTO ticket_order (id, order_no, user_id, session_id, total_amount_cents, idempotency_operation, idempotency_key, request_hash, expires_at) VALUES (?, ?, 900001, 900001, 29900, ?, 'same-key', UNHEX(SHA2('same-request', 256)), '2030-01-01')", id, orderNo, operation);
    }

    private void assertRejected(String sql, int mysqlErrorCode) {
        // MySQL CHECK 失败的 HY000 状态未必被 Spring 翻译为 DataIntegrityViolationException。
        var exception = assertThrows(DataAccessException.class, () -> jdbc.update(sql));
        assertThat(exception.getMostSpecificCause()).isInstanceOfSatisfying(SQLException.class,
                cause -> assertThat(cause.getErrorCode()).isEqualTo(mysqlErrorCode));
    }
}
