package com.ticketing;

import com.ticketing.dto.JoinWaitlistRequest;
import com.ticketing.dto.TicketWriteRequest;
import com.ticketing.entity.PurchaseRequest;
import com.ticketing.enums.RefundReason;
import com.ticketing.exception.BusinessException;
import com.ticketing.security.TicketCredentialSigner;
import com.ticketing.service.AccountUserDetailsService;
import com.ticketing.service.ElectronicTicketService;
import com.ticketing.service.OrderRefundService;
import com.ticketing.service.OrderService;
import com.ticketing.service.PurchaseAdmission;
import com.ticketing.service.PurchaseOrderWorker;
import com.ticketing.service.RedisStockReservationService;
import com.ticketing.mapper.PurchaseRequestMapper;
import com.ticketing.service.StockSnapshotService;
import com.ticketing.service.TicketService;
import com.ticketing.service.TradeResourceLocker;
import com.ticketing.service.WaitlistDispatcher;
import com.ticketing.service.WaitlistService;

import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.annotation.DirtiesContext;
import static org.assertj.core.api.Assertions.*;

/** 每个用例使用新建虚构活动；锁、库存、订单、票券与同步生命周期均使用真实MySQL。 */
@SpringBootTest(properties={"ticketing.purchase.queue-seconds=600"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StockBucketIT {
    @Autowired JdbcTemplate db;
    @Autowired PurchaseAdmission admission;
    @Autowired RedisStockReservationService stocks;
    @Autowired PurchaseRequestMapper requests;
    @Autowired StockSnapshotService snapshots;
    @Autowired PurchaseOrderWorker worker;
    @Autowired OrderService orders;
    @Autowired OrderRefundService refunds;
    @Autowired TradeResourceLocker resources;
    @Autowired TicketService management;
    @Autowired WaitlistService waitlist;
    @Autowired WaitlistDispatcher dispatcher;
    @Autowired ElectronicTicketService tickets;
    @Autowired TicketCredentialSigner signer;
    @Autowired AccountUserDetailsService identities;
    @Autowired PlatformTransactionManager manager;
    static final String HASH = new BCryptPasswordEncoder().encode(UUID.randomUUID().toString());
    long activity, session, sku, buyer, admin;

    @BeforeEach void setup() {
        assertThat(System.getenv("MYSQL_HOST")).isEqualTo("127.0.0.1");
        assertThat(System.getenv("MYSQL_PORT")).isEqualTo("13307");
        assertThat(db.queryForObject("SELECT token FROM performance_control.environment_guard",String.class))
                .isEqualTo(System.getenv("RECOVERY_TEST_GUARD"));
        buyer = user("USER"); admin = user("ADMIN");
        String tag = "bucket_" + UUID.randomUUID();
        db.update("INSERT INTO activity(title,category,city,venue,status,published_at) VALUES(?,'CONCERT','上海','隔离分桶验收','PUBLISHED',UTC_TIMESTAMP(6))",tag);
        activity = db.queryForObject("SELECT id FROM activity WHERE title=?",Long.class,tag);
        session = session(); sku = sku(session, 4, 4);
    }
    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }

    long user(String role) {
        String name="s17_"+UUID.randomUUID().toString().replace("-","");
        db.update("INSERT INTO user_account(username,password_hash,role) VALUES(?,?,?)",name,HASH,role);
        return db.queryForObject("SELECT id FROM user_account WHERE username=?",Long.class,name);
    }
    long session() {
        db.update("INSERT INTO activity_session(activity_id,title,starts_at,ends_at,sale_starts_at) VALUES(?,'分桶场次',TIMESTAMPADD(HOUR,1,UTC_TIMESTAMP(6)),TIMESTAMPADD(HOUR,3,UTC_TIMESTAMP(6)),TIMESTAMPADD(HOUR,-1,UTC_TIMESTAMP(6)))",activity);
        return db.queryForObject("SELECT MAX(id) FROM activity_session WHERE activity_id=?",Long.class,activity);
    }
    long sku(long parent, int total, int count) {
        db.update("INSERT INTO ticket_sku(session_id,name,price_cents,total_stock,max_quantity_per_order,status,sale_starts_at,sale_ends_at) VALUES(?,?,100,?,4,'ON_SALE',TIMESTAMPADD(MINUTE,-30,UTC_TIMESTAMP(6)),TIMESTAMPADD(MINUTE,30,UTC_TIMESTAMP(6)))",parent,"分桶票"+UUID.randomUUID(),total);
        long id=db.queryForObject("SELECT MAX(id) FROM ticket_sku WHERE session_id=?",Long.class,parent);
        if(count>0) tx().executeWithoutResult(s->{
            for(int n=0;n<count;n++) db.update("INSERT INTO ticket_stock_bucket(sku_id,bucket_no,total_stock) VALUES(?,?,?)",id,n,total/count+(n<total%count?1:0));
            db.update("INSERT INTO stock_strategy_switch(sku_id,bucket_count,operator_id,backup_sha256,previous_locked,previous_sold) VALUES(?,?,?,SHA2(?,256),0,0)",id,count,admin,"隔离空票档夹具"+id);
            db.update("UPDATE ticket_sku SET inventory_strategy='BUCKETED',bucket_count=? WHERE id=?",count,id);
        });
        return id;
    }
    TransactionTemplate tx() { var tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);return tx; }
    PurchaseRequest claim(long owner,long ticket,int quantity) {
        PurchaseTestSupport.prepare(snapshots,ticket);
        long id=Long.parseLong(admission.accept(owner,Long.toString(ticket),quantity,UUID.randomUUID().toString()).requestId());
        return Objects.requireNonNull(stocks.find(id));
    }
    long order(long owner,long ticket,int quantity) {
        PurchaseRequest claim=claim(owner,ticket,quantity);worker.process(claim.id());
        assertThat(requests.find(claim.id()).status()).isEqualTo("SUCCEEDED");
        return requests.find(claim.id()).orderId();
    }
    void stock(long ticket,int locked,int sold) {
        var row=db.queryForMap("SELECT * FROM ticket_stock_balance WHERE id=?",ticket);
        assertThat(((Number)row.get("locked_stock")).intValue()).isEqualTo(locked);
        assertThat(((Number)row.get("sold_stock")).intValue()).isEqualTo(sold);
        assertThat(db.queryForObject("SELECT locked_stock+sold_stock FROM ticket_sku WHERE id=?",Integer.class,ticket)).isZero();
    }
    void consistent() {
        assertThat(db.queryForObject("""
                SELECT COUNT(*) FROM ticket_stock_bucket b JOIN ticket_sku t ON t.id=b.sku_id
                JOIN activity_session s ON s.id=t.session_id
                LEFT JOIN (SELECT a.sku_id,a.bucket_no,SUM(IF(o.status='PENDING',a.quantity,0)) locked_qty,
                  SUM(IF(o.status='PAID',a.quantity,0)) sold_qty FROM order_stock_allocation a
                  JOIN ticket_order o ON o.id=a.order_id GROUP BY a.sku_id,a.bucket_no) x
                  ON x.sku_id=b.sku_id AND x.bucket_no=b.bucket_no
                WHERE s.activity_id=? AND (b.locked_stock<>COALESCE(x.locked_qty,0) OR b.sold_stock<>COALESCE(x.sold_qty,0))
                """,Integer.class,activity)).isZero();
        assertThat(db.queryForObject("""
                SELECT COUNT(*) FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id
                JOIN ticket_sku t ON t.id=i.sku_id JOIN activity_session s ON s.id=t.session_id
                LEFT JOIN (SELECT order_id,SUM(quantity) n FROM order_stock_allocation GROUP BY order_id) a ON a.order_id=o.id
                WHERE s.activity_id=? AND t.inventory_strategy='BUCKETED' AND o.status IN('PENDING','PAID') AND COALESCE(a.n,0)<>i.quantity
                """,Integer.class,activity)).isZero();
    }
    void resize(long ticket,int total) {
        String name=db.queryForObject("SELECT username FROM user_account WHERE id=?",String.class,admin);
        var principal=identities.loadUserByUsername(name);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities()));
        try {
            var row=db.queryForMap("SELECT * FROM ticket_sku WHERE id=?",ticket);
            management.update(ticket,new TicketWriteRequest((String)row.get("name"),"100",total,
                    db.queryForObject("SELECT sale_starts_at FROM ticket_sku WHERE id=?",java.time.LocalDateTime.class,ticket).atOffset(ZoneOffset.UTC),
                    db.queryForObject("SELECT sale_ends_at FROM ticket_sku WHERE id=?",java.time.LocalDateTime.class,ticket).atOffset(ZoneOffset.UTC),4,((Number)row.get("version")).intValue()));
        } finally {SecurityContextHolder.clearContext();}
    }

    @Test void fragmentedThreeBucketReservationPaysAndRefundsOnlyOriginalAllocations() {
        long id=order(buyer,sku,3);
        var original=db.queryForList("SELECT bucket_no,quantity FROM order_stock_allocation WHERE order_id=? ORDER BY bucket_no",id);
        assertThat(original).hasSize(3);stock(sku,3,0);
        var payment=orders.pay(buyer,id);assertThat(orders.pay(buyer,id).paymentNo()).isEqualTo(payment.paymentNo());stock(sku,0,3);
        assertThat(refunds.refund(admin,id,RefundReason.CUSTOMER_REQUEST).status()).isEqualTo("SUCCEEDED");
        assertThat(refunds.refund(admin,id,RefundReason.CUSTOMER_REQUEST).status()).isEqualTo("SUCCEEDED");stock(sku,0,0);
        assertThat(db.queryForList("SELECT bucket_no,quantity FROM order_stock_allocation WHERE order_id=? ORDER BY bucket_no",id)).isEqualTo(original);
        assertThat(db.queryForObject("SELECT version FROM ticket_sku WHERE id=?",Integer.class,sku)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM electronic_ticket WHERE order_id=? AND status='VOID'",Integer.class,id)).isEqualTo(3);
        assertThat(db.queryForObject("SELECT paid_at FROM ticket_order WHERE id=?",java.time.LocalDateTime.class,id)).isNotNull();consistent();
    }
    @Test void cancellationAndExpiryReleaseOriginalBucketsExactlyOnce() {
        long first=order(buyer,sku,2);orders.cancel(buyer,first);orders.cancel(buyer,first);stock(sku,0,0);
        long second=order(buyer,sku,4);
        db.update("UPDATE ticket_order SET created_at=TIMESTAMPADD(MINUTE,-10,UTC_TIMESTAMP(6)),expires_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE id=?",second);
        assertThat(orders.expire(buyer,second)).isTrue();assertThat(orders.expire(buyer,second)).isFalse();stock(sku,0,0);consistent();
    }
    @Test void insufficientGlobalStockRollsBackEveryPartialAllocationAndQuota() {
        order(buyer,sku,3);long other=user("USER");PurchaseRequest attempt=claim(other,sku,2);worker.process(attempt.id());
        assertThat(requests.find(attempt.id()).status()).isEqualTo("FAILED");stock(sku,3,0);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ticket_order WHERE user_id=?",Integer.class,other)).isZero();
        assertThat(db.queryForObject("SELECT COALESCE(SUM(occupied_quantity),0) FROM user_session_quota WHERE user_id=?",Integer.class,other)).isZero();consistent();
    }
    @Test void oneHundredDistinctBuyersNeverOversellOrLeavePartialAllocations() throws Exception {
        long hot=sku(session,60,4);var claims=new ArrayList<PurchaseRequest>();
        for(int n=0;n<100;n++) claims.add(claim(user("USER"),hot,1));
        try(var pool=Executors.newFixedThreadPool(16)) {
            var futures=new ArrayList<Future<?>>();
            for(var request:claims) futures.add(pool.submit(()->{try {worker.process(request.id());} catch(ConcurrencyFailureException retry) { /* 持久PROCESSING留给下轮真实补偿。 */ }}));
            for(var future:futures)future.get(60,TimeUnit.SECONDS);
        }
        for(var request:claims) if(requests.find(request.id())==null)worker.process(request.id());
        assertThat(claims.stream().filter(c->"SUCCEEDED".equals(requests.find(c.id()).status())).count()).isEqualTo(60);
        assertThat(claims.stream().filter(c->"FAILED".equals(requests.find(c.id()).status())).count()).isEqualTo(40);
        stock(hot,60,0);consistent();
    }
    @Test void sameBuyerAcrossBucketsAndTicketsStillSharesOneSessionQuota() throws Exception {
        long second=sku(session,8,4);long otherSession=session();long third=sku(otherSession,8,4);
        var claims=List.of(claim(buyer,sku,2),claim(buyer,second,2),claim(buyer,second,2),claim(buyer,third,2));
        try(var pool=Executors.newFixedThreadPool(4)) {
            var tasks=claims.stream().map(c->pool.submit(()->worker.process(c.id()))).toList();
            for(var task:tasks)task.get(30,TimeUnit.SECONDS);
        }
        assertThat(db.queryForObject("SELECT occupied_quantity FROM user_session_quota WHERE user_id=? AND session_id=?",Integer.class,buyer,session)).isEqualTo(4);
        assertThat(db.queryForObject("SELECT occupied_quantity FROM user_session_quota WHERE user_id=? AND session_id=?",Integer.class,buyer,otherSession)).isEqualTo(2);consistent();
    }
    @Test void fifoHeadCombinesBucketsAndInsufficientHeadIsNeverSkipped() {
        long empty=sku(session,0,4);long other=user("USER");
        var head=waitlist.join(buyer,new JoinWaitlistRequest(Long.toString(empty),2));
        var tail=waitlist.join(other,new JoinWaitlistRequest(Long.toString(empty),1));
        resize(empty,1);assertThat(dispatcher.dispatch(empty)).isZero();
        assertThat(waitlist.detail(other,Long.parseLong(tail.id())).status()).isEqualTo("WAITING");
        resize(empty,2);assertThat(dispatcher.dispatch(empty)).isEqualTo(1);
        var allocated=waitlist.detail(buyer,Long.parseLong(head.id()));
        orders.cancel(buyer,Long.parseLong(allocated.reservedOrderId()));
        assertThat(dispatcher.dispatch(empty)).isEqualTo(1);consistent();
    }
    @Test void invalidHeadExpiresAndConcurrentDispatchAllocatesEachWaiterOnce() throws Exception {
        long empty=sku(session,0,4);var ids=new ArrayList<Long>();
        for(int n=0;n<20;n++) ids.add(Long.parseLong(waitlist.join(user("USER"),new JoinWaitlistRequest(Long.toString(empty),1)).id()));
        db.update("UPDATE waitlist_entry SET created_at=TIMESTAMPADD(MINUTE,-10,UTC_TIMESTAMP(6)),expires_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE id=?",ids.getFirst());
        resize(empty,20);
        try(var pool=Executors.newFixedThreadPool(4)) {
            var tasks=new ArrayList<Future<?>>();for(int n=0;n<4;n++)tasks.add(pool.submit(()->dispatcher.dispatch(empty)));
            for(var task:tasks)task.get(30,TimeUnit.SECONDS);
        }
        assertThat(db.queryForObject("SELECT status FROM waitlist_entry WHERE id=?",String.class,ids.getFirst())).isEqualTo("EXPIRED");
        var allocated=db.queryForList("SELECT reserved_order_id FROM waitlist_entry WHERE sku_id=? AND status='ALLOCATED' ORDER BY id",Long.class,empty);
        assertThat(allocated).hasSize(19).isSorted();consistent();
    }
    @Test void managementReadsLiveTotalsAndCannotShrinkBelowOccupiedStock() {
        order(buyer,sku,3);assertThatThrownBy(()->resize(sku,2)).isInstanceOf(com.ticketing.exception.BusinessException.class);stock(sku,3,0);
        resize(sku,8);var view=management.publicList(activity,session).stream().filter(t->t.id().equals(Long.toString(sku))).findFirst().orElseThrow();
        assertThat(view.lockedStock()).isEqualTo(3);assertThat(view.availableStock()).isEqualTo(5);
        assertThat(db.queryForObject("SELECT SUM(total_stock) FROM ticket_stock_bucket WHERE sku_id=?",Integer.class,sku)).isEqualTo(8);consistent();
    }
    @Test void missingAllocationFailsClosedBeforePaymentAndDoesNotUseLegacyCounters() {
        long id=order(buyer,sku,2);var rows=db.queryForList("SELECT * FROM order_stock_allocation WHERE order_id=?",id);
        db.update("DELETE FROM order_stock_allocation WHERE order_id=?",id);
        assertThatThrownBy(()->orders.pay(buyer,id)).isInstanceOf(IllegalStateException.class);stock(sku,2,0);
        assertThat(db.queryForObject("SELECT status FROM ticket_order WHERE id=?",String.class,id)).isEqualTo("PENDING");
        for(var row:rows)db.update("INSERT INTO order_stock_allocation(order_id,sku_id,bucket_no,quantity) VALUES(?,?,?,?)",row.get("order_id"),row.get("sku_id"),row.get("bucket_no"),row.get("quantity"));
        orders.pay(buyer,id);stock(sku,0,2);consistent();
    }
    @Test void finalTransactionFailureRollsBackBucketsAllocationOrderAndRequestTogether() {
        PurchaseRequest request=claim(buyer,sku,3);
        assertThatThrownBy(()->tx().executeWithoutResult(s->{worker.process(request.id());throw new IllegalStateException("隔离提交前中断");})).isInstanceOf(IllegalStateException.class);
        stock(sku,0,0);assertThat(requests.find(request.id()).status()).isEqualTo("PROCESSING");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ticket_order WHERE user_id=?",Integer.class,buyer)).isZero();
        worker.process(request.id());worker.process(request.id());stock(sku,3,0);consistent();
    }

    @Test void usedTicketRefundRemainsManualAndCancelledSessionHandlesDisabledBuyer() {
        long id=order(buyer,sku,2);orders.pay(buyer,id);
        var ticket=db.queryForMap("SELECT ticket_no,credential_key_version FROM electronic_ticket WHERE order_id=? ORDER BY id LIMIT 1",id);
        tickets.checkIn(admin,signer.credential((String)ticket.get("ticket_no"),((Number)ticket.get("credential_key_version")).intValue()),false);
        assertThat(refunds.refund(admin,id,RefundReason.CUSTOMER_REQUEST).status()).isEqualTo("MANUAL_REQUIRED");stock(sku,0,2);
        long other=user("USER"),pending=order(other,sku,2);
        db.update("UPDATE user_account SET status='DISABLED' WHERE id=?",other);
        db.update("UPDATE activity_session SET status='CANCELLED' WHERE id=?",session);
        refunds.processCancelledSession(pending);refunds.processCancelledSession(pending);stock(sku,0,2);consistent();
    }

    PurchaseRequest routed(long owner,int number) {
        String key;
        do { key=UUID.randomUUID().toString(); } while(Math.floorMod(Long.hashCode(owner ^ (long)key.hashCode()),4)!=number);
        PurchaseTestSupport.prepare(snapshots,sku);
        long id=Long.parseLong(admission.accept(owner,Long.toString(sku),1,key).requestId());
        return stocks.find(id);
    }
    void awaitWaiter(String table) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime()<deadline) {
            int count=db.queryForObject("""
                    SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                    JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                    WHERE l.OBJECT_SCHEMA=DATABASE() AND l.OBJECT_NAME=?
                    """,Integer.class,table);
            if(count>0)return;
            try {Thread.sleep(20);} catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IllegalStateException(e);}
        }
        throw new AssertionError("没有观察到指定资源的真实MySQL等待");
    }
    @Test void aLockedBucketDoesNotExcludeAnotherBucketButManagementWaitsForParent() throws Exception {
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);var request=routed(user("USER"),1);
        try(var pool=Executors.newFixedThreadPool(3)) {
            var blocker=pool.submit(()->tx().executeWithoutResult(s->{
                resources.lockReservation(buyer,sku,true);
                db.queryForMap("SELECT * FROM ticket_stock_bucket WHERE sku_id=? AND bucket_no=0 FOR UPDATE",sku);
                held.countDown();try {assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();} catch(InterruptedException e) {throw new IllegalStateException(e);}
            }));
            try {
                assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
                pool.submit(()->worker.process(request.id())).get(5,TimeUnit.SECONDS);
                assertThat(requests.find(request.id()).status()).isEqualTo("SUCCEEDED");
                var editor=pool.submit(()->resize(sku,0));awaitWaiter("activity_session");
                release.countDown();blocker.get(5,TimeUnit.SECONDS);
                assertThatThrownBy(()->editor.get(5,TimeUnit.SECONDS)).hasCauseInstanceOf(com.ticketing.exception.BusinessException.class);
            } finally {release.countDown();}
        }
        stock(sku,1,0);consistent();
    }
    @Test void managerOffSaleCommitIsRecheckedAfterConsumerWaits() throws Exception {
        PurchaseRequest request=claim(buyer,sku,2);var held=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var editor=pool.submit(()->tx().executeWithoutResult(s->{
                db.queryForMap("SELECT id FROM activity_session WHERE id=? FOR UPDATE",session);
                db.update("UPDATE ticket_sku SET status='OFF_SALE' WHERE id=?",sku);held.countDown();
                try {release.await(10,TimeUnit.SECONDS);} catch(InterruptedException e) {throw new IllegalStateException(e);}
            }));
            try {
                assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();var consumer=pool.submit(()->worker.process(request.id()));
                awaitWaiter("activity_session");release.countDown();editor.get(5,TimeUnit.SECONDS);consumer.get(5,TimeUnit.SECONDS);
            } finally {release.countDown();}
        }
        assertThat(requests.find(request.id()).status()).isEqualTo("FAILED");stock(sku,0,0);consistent();
    }
    @Test void genuineMysqlDeadlockRollsBackVictimAndConsumerRecoversWithoutPartialBuckets() throws Exception {
        PurchaseRequest request=claim(buyer,sku,3);var held=new CountDownLatch(1);
        var deadlockVictims=new java.util.concurrent.atomic.AtomicInteger();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var fault=pool.submit(()->{
                try {tx().executeWithoutResult(s->{
                    db.queryForMap("SELECT * FROM ticket_stock_bucket WHERE sku_id=? AND bucket_no=1 FOR UPDATE",sku);held.countDown();
                    awaitWaiter("ticket_stock_bucket");
                    // 仅隔离故障连接故意反序；生产预占始终0→1→2。
                    db.queryForMap("SELECT * FROM ticket_stock_bucket WHERE sku_id=? AND bucket_no=0 FOR UPDATE",sku);
                });} catch(ConcurrencyFailureException expectedVictim) { deadlockVictims.incrementAndGet(); }
            });
            assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
            var consumer=pool.submit(()->worker.process(request.id()));fault.get(15,TimeUnit.SECONDS);consumer.get(15,TimeUnit.SECONDS);
        }
        assertThat(deadlockVictims.get()).isEqualTo(1);
        assertThat(requests.find(request.id()).status()).isEqualTo("SUCCEEDED");stock(sku,3,0);consistent();
    }
}
