package com.ticketing;

import com.ticketing.entity.PurchaseRequest;
import com.ticketing.service.PurchaseAdmission;
import com.ticketing.service.PurchaseOrderWorker;
import com.ticketing.service.PurchaseProcessing;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.assertj.core.api.Assertions.*;

/** 仅显式指定该类运行；先真实持久受理并认领，再计量生产complete事务，排除消息发布。 */
@SpringBootTest(properties = {"ticketing.purchase.queue-seconds=600", "ticketing.purchase.lease-seconds=120",
        "ticketing.catalog-cache.enabled=false", "ticketing.purchase.admission-limit.enabled=false",
        "ticketing.messaging.jobs-enabled=false", "ticketing.purchase.jobs-enabled=false",
        "ticketing.purchase.messaging-enabled=false", "ticketing.waitlist.messaging.enabled=false",
        "ticketing.waitlist.jobs.enabled=false"})
class TradeKernelBenchmark {
    @Autowired JdbcTemplate db;
    @Autowired PurchaseAdmission admission;
    @Autowired PurchaseProcessing processing;
    @Autowired PurchaseOrderWorker worker;
    @Autowired ObjectMapper json;

    @Test void measureRealTransactions() throws Exception {
        assertThat(System.getenv("MYSQL_HOST")).isEqualTo("127.0.0.1");
        assertThat(System.getenv("MYSQL_PORT")).isEqualTo("13307");
        assertThat(db.queryForObject("SELECT token FROM performance_control.environment_guard", String.class))
                .isEqualTo(System.getenv("RECOVERY_TEST_GUARD"));
        Path output = Path.of(Objects.requireNonNull(System.getenv("TRADE_BENCH_RESULT"))).toAbsolutePath();
        assertThat(Files.exists(output)).isFalse();
        List<Map<String,Object>> rounds = new ArrayList<>();
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("environment", "ticketing-perf-only"); report.put("rounds", rounds);
        report.put("measurement", "先可靠受理及认领；并发调用生产worker.complete，计时含事务提交，不含Outbox传输及claim；96笔有限批次，非稳定窗口HTTP TPS");
        report.put("fixture", "BUCKETED测试模式仅在随机MySQL集成测试schema中创建桶，任务结束后由受保护验证器清理该schema；不模拟、不改写固定性能库的生产切换凭据");
        String passwordHash = new BCryptPasswordEncoder().encode(UUID.randomUUID().toString());
        try {
            List<InventoryMode> modes = List.of(new InventoryMode("SINGLE",0),new InventoryMode("BUCKETED",1),
                    new InventoryMode("BUCKETED",4),new InventoryMode("BUCKETED",8),new InventoryMode("BUCKETED",16));
            for (int pass=1;pass<=2;pass++) {
                List<InventoryMode> orderedModes=new ArrayList<>(modes);
                if(pass==2) Collections.reverse(orderedModes);
                for (String scenario : List.of("hot", "same-user-cross-session", "multi-ticket")) {
                for (InventoryMode mode : orderedModes) {
                for (int concurrency : List.of(1,2,4,8,16)) {
                    String tag = UUID.randomUUID().toString().replace("-", "");
                    db.update("INSERT INTO activity(title,category,city,venue,status,published_at) VALUES(?,'CONCERT','上海','隔离基准','PUBLISHED',UTC_TIMESTAMP(6))", tag);
                    long activity = db.queryForObject("SELECT id FROM activity WHERE title=?", Long.class, tag);
                    List<Long> skus = new ArrayList<>();
                    for (int index=0; index<(scenario.equals("same-user-cross-session")?8:1); index++) {
                        db.update("INSERT INTO activity_session(activity_id,title,starts_at,ends_at,sale_starts_at) VALUES(?,'基准',TIMESTAMPADD(DAY,1,UTC_TIMESTAMP(6)),TIMESTAMPADD(DAY,2,UTC_TIMESTAMP(6)),TIMESTAMPADD(DAY,-1,UTC_TIMESTAMP(6)))",activity);
                        long session=db.queryForObject("SELECT MAX(id) FROM activity_session WHERE activity_id=?",Long.class,activity);
                        for(int ticket=0;ticket<(scenario.equals("multi-ticket")?4:1);ticket++) {
                            db.update("INSERT INTO ticket_sku(session_id,name,price_cents,total_stock,max_quantity_per_order,status,sale_starts_at,sale_ends_at) VALUES(?,?,100,10000,4,'ON_SALE',TIMESTAMPADD(HOUR,-1,UTC_TIMESTAMP(6)),TIMESTAMPADD(HOUR,1,UTC_TIMESTAMP(6)))",session,"票档"+ticket);
                            long sku=db.queryForObject("SELECT MAX(id) FROM ticket_sku WHERE session_id=?",Long.class,session);
                            skus.add(sku);
                            if(mode.bucketCount()>0) {
                                for(int number=0;number<mode.bucketCount();number++)
                                    db.update("INSERT INTO ticket_stock_bucket(sku_id,bucket_no,total_stock) VALUES(?,?,?)",
                                            sku,number,10000/mode.bucketCount()+(number<10000%mode.bucketCount()?1:0));
                                assertThat(db.update("UPDATE ticket_sku SET inventory_strategy='BUCKETED',bucket_count=? WHERE id=?",mode.bucketCount(),sku)).isEqualTo(1);
                            }
                        }
                    }
                    List<Long> users=new ArrayList<>();
                    for(int index=0;index<(scenario.equals("same-user-cross-session")?12:96);index++) {
                        String name="bench_"+tag.substring(0,16)+"_"+index;
                        db.update("INSERT INTO user_account(username,password_hash) VALUES(?,?)",name,passwordHash);
                        users.add(db.queryForObject("SELECT id FROM user_account WHERE username=?",Long.class,name));
                    }
                    List<PurchaseRequest> claims=new ArrayList<>();
                    for(int index=0;index<96;index++) {
                        long user=users.get(scenario.equals("same-user-cross-session")?index/8:index);
                        long sku=skus.get(index%skus.size());
                        long id=Long.parseLong(admission.accept(user,Long.toString(sku),1,"kernel-"+index).requestId());
                        claims.add(Objects.requireNonNull(processing.claim(id)));
                    }
                    long locksBefore=lockWaits(),deadlocksBefore=deadlocks(),statementsBefore=statementCount();
                    List<Double> times=Collections.synchronizedList(new ArrayList<>());
                    ExecutorService pool=Executors.newFixedThreadPool(concurrency);
                    long start=System.nanoTime();
                    try {
                        List<Future<?>> tasks=new ArrayList<>();
                        for(PurchaseRequest claim:claims) tasks.add(pool.submit(()->{
                            long begun=System.nanoTime(); worker.complete(claim);
                            times.add((System.nanoTime()-begun)/1_000_000.0);
                        }));
                        for(Future<?> task:tasks)task.get(60,TimeUnit.SECONDS);
                    } finally { pool.shutdownNow(); }
                    double seconds=(System.nanoTime()-start)/1_000_000_000.0;
                    long success=claims.stream().filter(c->"SUCCEEDED".equals(processing.find(c.id()).status())).count();
                    Collections.sort(times);
                    Map<String,Object> row=new LinkedHashMap<>();
                    row.put("scenario",scenario);row.put("concurrency",concurrency);row.put("requests",96);
                    row.put("success",success);row.put("elapsedSeconds",seconds);row.put("transactionTps",success/seconds);
                    row.put("transactionMs",Map.of("p50",percentile(times,.5),"p95",percentile(times,.95),"p99",percentile(times,.99)));
                    row.put("samplesMs",times);row.put("mysqlRowLockWaitMs",lockWaits()-locksBefore);
                    row.put("mysqlDeadlockDelta",deadlocks()-deadlocksBefore);
                    row.put("mysqlStatementDelta",Math.max(0,statementCount()-statementsBefore));
                    row.put("inventoryStrategy",mode.strategy());row.put("bucketCount",mode.bucketCount());row.put("pass",pass);
                    row.put("aggregateLocked",db.queryForObject("SELECT COALESCE(SUM(locked_stock),0) FROM ticket_stock_balance WHERE session_id IN (SELECT id FROM activity_session WHERE activity_id=?)",Long.class,activity));
                    row.put("legacyParentCounters",db.queryForObject("SELECT COALESCE(SUM(CAST(locked_stock AS SIGNED)+sold_stock),0) FROM ticket_sku WHERE session_id IN (SELECT id FROM activity_session WHERE activity_id=?)",Long.class,activity));
                    row.put("inventoryAudit",inventoryAudit(activity,mode));
                    row.put("allocationAudit",allocationAudit(activity,mode));
                    row.put("quotaAudit",quotaAudit(activity));
                    rounds.add(row);
                    assertThat(success).isEqualTo(96);assertThat(row.get("inventoryAudit")).isEqualTo(true);
                    assertThat(row.get("allocationAudit")).isEqualTo(true);assertThat(row.get("quotaAudit")).isEqualTo(true);
                    assertThat(db.queryForObject("SELECT pending_count FROM purchase_capacity WHERE id=1",Integer.class)).isZero();
                }
                }
                }
            }
            report.put("passed",true);
        } finally {
            Files.createDirectories(output.getParent());json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);
        }
    }
    record InventoryMode(String strategy,int bucketCount) { }
    long lockWaits(){return Long.parseLong(db.queryForMap("SHOW GLOBAL STATUS LIKE 'Innodb_row_lock_time'").get("Value").toString());}
    long deadlocks(){return db.queryForObject("SELECT COALESCE((SELECT COUNT FROM information_schema.innodb_metrics WHERE NAME='lock_deadlocks'),0)",Long.class);}
    long statementCount(){return db.queryForObject("SELECT COALESCE(SUM(COUNT_STAR),0) FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME=DATABASE()",Long.class);}
    double percentile(List<Double> values,double p){return values.get(Math.min(values.size()-1,(int)Math.ceil(values.size()*p)-1));}
    boolean inventoryAudit(long activity,InventoryMode mode) {
        long actual=db.queryForObject("SELECT COALESCE(SUM(locked_stock),0) FROM ticket_stock_balance WHERE session_id IN (SELECT id FROM activity_session WHERE activity_id=?)",Long.class,activity);
        long parent=db.queryForObject("SELECT COALESCE(SUM(CAST(locked_stock AS SIGNED)+sold_stock),0) FROM ticket_sku WHERE session_id IN (SELECT id FROM activity_session WHERE activity_id=?)",Long.class,activity);
        long bucketViolations=db.queryForObject("SELECT COUNT(*) FROM ticket_stock_bucket b LEFT JOIN (SELECT sku_id,bucket_no,SUM(quantity) quantity FROM order_stock_allocation GROUP BY sku_id,bucket_no) a ON a.sku_id=b.sku_id AND a.bucket_no=b.bucket_no WHERE b.sku_id IN(SELECT t.id FROM ticket_sku t JOIN activity_session s ON s.id=t.session_id WHERE s.activity_id=?) AND (b.locked_stock<>COALESCE(a.quantity,0) OR b.sold_stock<>0 OR b.total_stock< b.locked_stock)",Long.class,activity);
        return actual==96 && bucketViolations==0 && (mode.bucketCount()==0?parent==96:parent==0);
    }
    boolean allocationAudit(long activity,InventoryMode mode) {
        long violations=db.queryForObject("SELECT COUNT(*) FROM ticket_order_item i JOIN ticket_order o ON o.id=i.order_id JOIN ticket_sku t ON t.id=i.sku_id LEFT JOIN (SELECT order_id,sku_id,SUM(quantity) quantity FROM order_stock_allocation GROUP BY order_id,sku_id) a ON a.order_id=i.order_id AND a.sku_id=i.sku_id WHERE t.session_id IN(SELECT id FROM activity_session WHERE activity_id=?) AND ((t.inventory_strategy='BUCKETED' AND o.status IN('PENDING','PAID') AND COALESCE(a.quantity,0)<>i.quantity) OR (t.inventory_strategy='SINGLE' AND a.order_id IS NOT NULL) OR (a.quantity IS NOT NULL AND a.quantity<>i.quantity))",Long.class,activity);
        return violations==0;
    }
    boolean quotaAudit(long activity) {
        if(db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='user_session_quota'",Integer.class)==0)return true;
        return db.queryForObject("SELECT COUNT(*) FROM (SELECT o.user_id,o.session_id,SUM(i.quantity) n FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id JOIN activity_session s ON s.id=o.session_id WHERE s.activity_id=? AND o.status IN('PENDING','PAID') GROUP BY o.user_id,o.session_id) x LEFT JOIN user_session_quota q ON q.user_id=x.user_id AND q.session_id=x.session_id WHERE q.user_id IS NULL OR q.occupied_quantity<>x.n",Integer.class,activity)==0;
    }
}
