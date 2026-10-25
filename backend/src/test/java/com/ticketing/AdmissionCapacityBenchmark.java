package com.ticketing;

import com.ticketing.service.PurchaseAdmission;
import com.ticketing.service.PurchaseProcessing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.assertj.core.api.Assertions.assertThat;

/** 显式执行的一万个独立账号持久受理批次，排除HTTP/认证/消息消费，不能当作成交TPS。 */
@SpringBootTest(properties = {"ticketing.purchase.capacity=10000", "ticketing.purchase.queue-seconds=900"})
class AdmissionCapacityBenchmark {
    @Autowired JdbcTemplate db;
    @Autowired DataSource dataSource;
    @Autowired PurchaseAdmission admission;
    @Autowired PurchaseProcessing processing;
    @Autowired ObjectMapper json;

    @Test
    void measureTenThousandDistinctAccountsWithOneBoundedExecutor() throws Exception {
        assertThat(System.getenv("MYSQL_HOST")).isEqualTo("127.0.0.1");
        assertThat(System.getenv("MYSQL_PORT")).isEqualTo("13307");
        assertThat(db.queryForObject("SELECT token FROM performance_control.environment_guard", String.class))
                .isEqualTo(System.getenv("RECOVERY_TEST_GUARD"));
        Path output = Path.of(Objects.requireNonNull(System.getenv("ADMISSION_CAPACITY_BENCH_RESULT"))).toAbsolutePath();
        assertThat(Files.exists(output)).isFalse();
        String tag = "capacity_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String hash = new BCryptPasswordEncoder().encode(UUID.randomUUID().toString());
        List<Object[]> accounts = new ArrayList<>();
        for (int index = 0; index < 10000; index++) accounts.add(new Object[]{tag + "_" + index, hash});
        db.batchUpdate("INSERT INTO user_account(username,password_hash) VALUES(?,?)", accounts);
        List<Long> users = db.queryForList("SELECT id FROM user_account WHERE username LIKE ? ORDER BY id", Long.class, tag + "_%");
        assertThat(users).hasSize(10000);
        db.update("INSERT INTO activity(title,category,city,venue,status,published_at) VALUES(?,'CONCERT','上海','隔离容量基准','PUBLISHED',UTC_TIMESTAMP(6))",tag);
        long activity=db.queryForObject("SELECT id FROM activity WHERE title=?",Long.class,tag);
        db.update("INSERT INTO activity_session(activity_id,title,starts_at,ends_at,sale_starts_at) VALUES(?,'容量基准',TIMESTAMPADD(DAY,1,UTC_TIMESTAMP(6)),TIMESTAMPADD(DAY,2,UTC_TIMESTAMP(6)),TIMESTAMPADD(DAY,-1,UTC_TIMESTAMP(6)))",activity);
        long session=db.queryForObject("SELECT id FROM activity_session WHERE activity_id=?",Long.class,activity);
        db.update("INSERT INTO ticket_sku(session_id,name,price_cents,total_stock,status,sale_starts_at,sale_ends_at) VALUES(?,'容量基准票',100,10000,'ON_SALE',TIMESTAMPADD(HOUR,-1,UTC_TIMESTAMP(6)),TIMESTAMPADD(HOUR,1,UTC_TIMESTAMP(6)))",session);
        long sku=db.queryForObject("SELECT id FROM ticket_sku WHERE session_id=?",Long.class,session);
        String schema=dbSchema();
        long rowLockBefore = lockWaitMs();
        List<Double> durations = Collections.synchronizedList(new ArrayList<>());
        List<Long> requests = Collections.synchronizedList(new ArrayList<>());
        var running = new AtomicBoolean(true);
        var poolWaiting = new AtomicInteger();
        var tableSamples = new HashMap<String, Integer>();
        var monitorFailures = new ArrayList<String>();
        var hikari = dataSource.unwrap(HikariDataSource.class);
        Thread monitor = Thread.ofPlatform().daemon().start(() -> {
            try (var connection = DriverManager.getConnection(
                    "jdbc:mysql://127.0.0.1:13307/performance_schema", System.getenv("MYSQL_USER"), System.getenv("MYSQL_PASSWORD"));
                 var statement = connection.createStatement()) {
                while (running.get()) {
                    poolWaiting.accumulateAndGet(hikari.getHikariPoolMXBean().getThreadsAwaitingConnection(), Math::max);
                    try (var rows = statement.executeQuery("""
                            SELECT l.OBJECT_NAME,COUNT(*) n FROM data_lock_waits w JOIN data_locks l
                            ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID AND l.ENGINE=w.ENGINE
                            WHERE l.OBJECT_SCHEMA='""" + schema + "' GROUP BY l.OBJECT_NAME")) {
                        while (rows.next()) tableSamples.merge(rows.getString(1), rows.getInt(2), Integer::sum);
                    }
                    Thread.sleep(200);
                }
            } catch (Exception failure) { monitorFailures.add(failure.getClass().getSimpleName()); }
        });
        double seconds;
        try (var threads = Executors.newFixedThreadPool(64)) {
            long started = System.nanoTime();
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (long user : users) futures.add(threads.submit(() -> {
                long begin = System.nanoTime();
                requests.add(Long.parseLong(admission.accept(user, Long.toString(sku), 1, "capacity-once").requestId()));
                durations.add((System.nanoTime() - begin) / 1_000_000.0);
            }));
            try {
                for (var future : futures) future.get(600, TimeUnit.SECONDS);
            } finally {
                running.set(false);
                monitor.join(5000);
            }
            seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        }
        assertThat(monitorFailures).isEmpty();
        assertThat(requests).hasSize(10000);
        assertThat(db.queryForObject("SELECT COUNT(DISTINCT user_id) FROM purchase_request", Integer.class)).isEqualTo(10000);
        assertCapacity(10000);
        long first = requests.getFirst();
        for (int index = 0; index < 3; index++) processing.recover(first); // 尚未到期的恢复不释放。
        assertCapacity(10000);
        db.update("UPDATE purchase_request SET accepted_at=TIMESTAMPADD(SECOND,-10,UTC_TIMESTAMP(6)),deadline_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE id=?", first);
        for (int index = 0; index < 3; index++) processing.recover(first);
        assertCapacity(9999);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ticket_order", Integer.class)).isZero();
        Collections.sort(durations);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("passed", true);
        report.put("environment", "ticketing-perf-only");
        report.put("distinctAccounts", 10000);
        report.put("concurrency", 64);
        report.put("persistentCapacity", 10000);
        report.put("accepted", 10000);
        report.put("seconds", seconds);
        report.put("finiteBatchAcceptanceTps", 10000 / seconds);
        report.put("acceptedMs", Map.of("p50", percentile(durations, .5), "p95", percentile(durations, .95), "p99", percentile(durations, .99)));
        report.put("mysqlRowLockWaitMs", lockWaitMs() - rowLockBefore);
        report.put("sampledWaitingTables", tableSamples);
        report.put("maximumPoolWaiting", poolWaiting.get());
        report.put("repeatedExpiryReleasedExactlyOnce", true);
        report.put("scope", "一万个随机非实名隔离账号，调用真实PurchaseAdmission持久事务；跳过HTTP/BCrypt登录/预检/Redis/Outbox发布/消费者，正式订单为零。有限批次不是稳定HTTP或成交TPS，也不能用总锁等待单独归因容量行。");
        Files.createDirectories(output.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
    }

    String dbSchema() { return db.queryForObject("SELECT DATABASE()", String.class); }
    long lockWaitMs() { return Long.parseLong(db.queryForMap("SHOW GLOBAL STATUS LIKE 'Innodb_row_lock_time'").get("Value").toString()); }
    double percentile(List<Double> values, double fraction) { return values.get((int) Math.ceil(values.size() * fraction) - 1); }
    void assertCapacity(int expected) {
        assertThat(db.queryForObject("SELECT pending_count FROM purchase_capacity WHERE id=1", Integer.class)).isEqualTo(expected);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_request WHERE status IN('QUEUED','PROCESSING')", Integer.class)).isEqualTo(expected);
    }
}
