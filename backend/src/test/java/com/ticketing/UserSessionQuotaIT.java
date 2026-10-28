package com.ticketing;

import com.ticketing.dto.JoinWaitlistRequest;
import com.ticketing.dto.TicketWriteRequest;
import com.ticketing.dto.VersionRequest;
import com.ticketing.enums.RefundReason;
import com.ticketing.exception.BusinessException;
import com.ticketing.mapper.OrderMapper;
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
import com.ticketing.service.UserSessionQuotaService;
import com.ticketing.service.WaitlistService;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 真实MySQL精确额度与完整生命周期。仅订单明细故障点使用Spy，所有锁及状态SQL真实执行。 */
@SpringBootTest(properties={"ticketing.purchase.queue-seconds=600",
        "ticketing.checkin.opens-before-minutes=120"})
class UserSessionQuotaIT {
    @Autowired JdbcTemplate db;
    @Autowired PurchaseAdmission admission;
    @Autowired RedisStockReservationService stocks;
    @Autowired PurchaseRequestMapper requests;
    @Autowired StockSnapshotService snapshots;
    @Autowired PurchaseOrderWorker worker;
    @Autowired TradeResourceLocker resources;
    @Autowired UserSessionQuotaService quotas;
    @Autowired OrderService orders;
    @Autowired OrderRefundService refunds;
    @Autowired WaitlistService waitlist;
    @Autowired com.ticketing.service.WaitlistDispatcher dispatcher;
    @Autowired ElectronicTicketService tickets;
    @Autowired PlatformTransactionManager manager;
    @Autowired com.ticketing.service.TicketService ticketManagement;
    @Autowired com.ticketing.service.AccountUserDetailsService identities;
    @Autowired org.mybatis.spring.SqlSessionTemplate mybatis;
    @Autowired javax.sql.DataSource dataSource;
    @MockitoSpyBean OrderMapper orderMapper;
    static final String HASH=new BCryptPasswordEncoder().encode(UUID.randomUUID().toString());
    long user,admin,activity,session,sku;
    @BeforeEach void fixture() {
        user=user("USER");admin=user("ADMIN");
        String title="quota_"+UUID.randomUUID();
        db.update("INSERT INTO activity(title,category,city,venue,status,published_at) VALUES(?,'CONCERT','上海','隔离验收','PUBLISHED',UTC_TIMESTAMP(6))",title);
        activity=db.queryForObject("SELECT id FROM activity WHERE title=?",Long.class,title);
        session=session();sku=sku(session,100);
    }
    @AfterEach void resetFaults(){reset(orderMapper);}
    long user(String role) {
        String name="quota_"+UUID.randomUUID().toString().replace("-","").substring(0,20);
        db.update("INSERT INTO user_account(username,password_hash,role) VALUES(?,?,?)",name,HASH,role);
        return db.queryForObject("SELECT id FROM user_account WHERE username=?",Long.class,name);
    }
    long session() {
        db.update("INSERT INTO activity_session(activity_id,title,starts_at,ends_at,sale_starts_at) VALUES(?,'额度场次',TIMESTAMPADD(HOUR,1,UTC_TIMESTAMP(6)),TIMESTAMPADD(HOUR,3,UTC_TIMESTAMP(6)),TIMESTAMPADD(HOUR,-1,UTC_TIMESTAMP(6)))",activity);
        return db.queryForObject("SELECT MAX(id) FROM activity_session WHERE activity_id=?",Long.class,activity);
    }
    long sku(long parent,int stock) {
        db.update("INSERT INTO ticket_sku(session_id,name,price_cents,total_stock,max_quantity_per_order,status,sale_starts_at,sale_ends_at) VALUES(?,?,100,?,4,'ON_SALE',TIMESTAMPADD(MINUTE,-30,UTC_TIMESTAMP(6)),TIMESTAMPADD(MINUTE,30,UTC_TIMESTAMP(6)))",parent,"票档"+UUID.randomUUID(),stock);
        return db.queryForObject("SELECT MAX(id) FROM ticket_sku WHERE session_id=?",Long.class,parent);
    }
    long accept(long owner,long ticket,int quantity,String key){PurchaseTestSupport.prepare(snapshots,ticket);return Long.parseLong(admission.accept(owner,Long.toString(ticket),quantity,key).requestId());}
    long order(long owner,long ticket,int quantity) {
        long id=accept(owner,ticket,quantity,UUID.randomUUID().toString());worker.process(id);
        assertThat(requests.find(id).status()).isEqualTo("SUCCEEDED");return requests.find(id).orderId();
    }
    TransactionTemplate tx(){var tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);return tx;}
    void consistent() {
        assertThat(db.queryForObject("""
            SELECT COUNT(*) FROM (
              SELECT o.user_id,o.session_id,SUM(i.quantity) n FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id
              WHERE o.session_id IN (SELECT id FROM activity_session WHERE activity_id=?) AND o.status IN('PENDING','PAID')
              GROUP BY o.user_id,o.session_id
            ) x LEFT JOIN user_session_quota q ON q.user_id=x.user_id AND q.session_id=x.session_id
            WHERE q.user_id IS NULL OR q.occupied_quantity<>x.n
            """,Integer.class,activity)).isZero();
        assertThat(db.queryForObject("""
            SELECT COUNT(*) FROM user_session_quota q WHERE q.session_id IN(SELECT id FROM activity_session WHERE activity_id=?)
            AND q.occupied_quantity<>(SELECT COALESCE(SUM(i.quantity),0) FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id
              WHERE o.user_id=q.user_id AND o.session_id=q.session_id AND o.status IN('PENDING','PAID'))
            """,Integer.class,activity)).isZero();
    }
    <T> List<T> parallel(int count,IntFunction<T> action)throws Exception {
        var pool=Executors.newFixedThreadPool(Math.min(count,16));
        try {var tasks=new ArrayList<Future<T>>();for(int i=0;i<count;i++){final int n=i;tasks.add(pool.submit(()->action.apply(n)));}
            var values=new ArrayList<T>();for(var task:tasks)values.add(task.get(30,TimeUnit.SECONDS));return values;
        } finally {pool.shutdownNow();}
    }
    @Test void concurrentFirstRowsAcrossTicketsShareOneSessionLimitAndReplaysDoNotOccupyAgain()throws Exception {
        long other=sku(session,100);var ids=new ArrayList<Long>();
        for(int i=0;i<4;i++)ids.add(accept(user,i%2==0?sku:other,1,"first-"+i));
        parallel(ids.size(),i->{worker.process(ids.get(i));return true;});
        assertThat(ids.stream().filter(id->"SUCCEEDED".equals(requests.find(id).status())).count()).isEqualTo(4);
        assertThat(quotas.occupied(user,session)).isEqualTo(4);
        assertThat(orders.limits(user,session).remainingQuantity()).isZero();
        parallel(ids.size(),i->{worker.process(ids.get(i));return true;});
        assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_request WHERE status='QUEUED'",Integer.class)).isZero();consistent();
    }
    @Test void sameUserDifferentSessionsHoldSharedAccountAndIndependentQuotaConcurrentlyButDisableWaits()throws Exception {
        long second=session(),secondSku=sku(second,100);long request=accept(user,sku,1,"disable-after");
        var held=new CountDownLatch(2);var release=new CountDownLatch(1);var changing=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(3);
        try {
            var first=pool.submit(()->tx().executeWithoutResult(s->{resources.lockReservation(user,sku,true);held.countDown();await(release);}));
            var other=pool.submit(()->tx().executeWithoutResult(s->{resources.lockReservation(user,secondSku,true);held.countDown();await(release);}));
            assertThat(held.await(5,TimeUnit.SECONDS)).as("同一用户跨场次须同时进入，不受账号UPDATE串行化").isTrue();
            var disable=pool.submit(()->{changing.countDown();db.update("UPDATE user_account SET status='DISABLED' WHERE id=?",user);});
            assertThat(changing.await(1,TimeUnit.SECONDS)).isTrue();Thread.sleep(100);assertThat(disable.isDone()).isFalse();
            release.countDown();first.get(5,TimeUnit.SECONDS);other.get(5,TimeUnit.SECONDS);disable.get(5,TimeUnit.SECONDS);
            worker.process(request);assertThat(requests.find(request).failureCode()).isEqualTo("ACCOUNT_UNAVAILABLE");
            assertThat(quotas.occupied(user,session)).isZero();consistent();
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void cancelReleasesExactlyOnceAndPaymentNeverOccupiesAgain() {
        doAnswer(call->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                .as("取消响应投影必须在写事务提交后查询").isFalse();
            return mybatis.getMapper(OrderMapper.class).owned(call.getArgument(0),call.getArgument(1));
        }).when(orderMapper).owned(anyLong(),anyLong());
        long cancelled=order(user,sku,2);assertThat(quotas.occupied(user,session)).isEqualTo(2);
        orders.cancel(user,cancelled);orders.cancel(user,cancelled);assertThat(quotas.occupied(user,session)).isZero();
        long paid=order(user,sku,3);orders.pay(user,paid);orders.pay(user,paid);
        assertThat(quotas.occupied(user,session)).isEqualTo(3);consistent();
    }
    @Test void expiredDisabledBuyerReleasesExactlyOnce() {
        long id=order(user,sku,2);
        db.update("UPDATE ticket_order SET created_at=TIMESTAMPADD(MINUTE,-20,UTC_TIMESTAMP(6)),expires_at=TIMESTAMPADD(MINUTE,-1,UTC_TIMESTAMP(6)) WHERE id=?",id);
        db.update("UPDATE user_account SET status='DISABLED' WHERE id=?",user);
        assertThat(orders.expire(user,id)).isTrue();assertThat(orders.expire(user,id)).isFalse();
        assertThat(quotas.occupied(user,session)).isZero();consistent();
    }
    @Test void refundReleasesButRepeatedRefundAndUsedTicketManualHandlingDoNotReleaseAgain() {
        long id=order(user,sku,2);orders.pay(user,id);
        assertThat(refunds.refund(admin,id,RefundReason.CUSTOMER_REQUEST).status()).isEqualTo("SUCCEEDED");
        refunds.refund(admin,id,RefundReason.CUSTOMER_REQUEST);assertThat(quotas.occupied(user,session)).isZero();
        long used=order(user,sku,1);orders.pay(user,used);
        long ticket=db.queryForObject("SELECT id FROM electronic_ticket WHERE order_id=?",Long.class,used);
        tickets.checkIn(admin,tickets.credential(user,ticket).credential(),false);
        assertThat(refunds.refund(admin,used,RefundReason.CUSTOMER_REQUEST).status()).isEqualTo("MANUAL_REQUIRED");
        assertThat(quotas.occupied(user,session)).isEqualTo(1);consistent();
    }
    @Test void cancelledCommitSurvivesProjectionReadFailureAndRetryDoesNotReleaseAgain() {
        long id=order(user,sku,2);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("验收提交后读取故障"))
                .when(orderMapper).owned(id,user);
        assertThatThrownBy(()->orders.cancel(user,id)).isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThat(db.queryForObject("SELECT status FROM ticket_order WHERE id=?",String.class,id)).isEqualTo("CANCELLED");
        assertThat(quotas.occupied(user,session)).isZero();
        long events=db.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_type='TICKET_SKU' AND aggregate_id=?",Long.class,sku);
        reset(orderMapper);
        assertThat(orders.cancel(user,id).status()).isEqualTo("CANCELLED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_type='TICKET_SKU' AND aggregate_id=?",Long.class,sku)).isEqualTo(events);
        assertThat(quotas.occupied(user,session)).isZero();consistent();
    }

    @Test void cancelledSessionAftercareClosesAndRefundsDisabledBuyerWithoutDoubleRelease() {
        long pending=order(user,sku,1),paid=order(user,sku,2);orders.pay(user,paid);
        db.update("UPDATE user_account SET status='DISABLED' WHERE id=?",user);
        db.update("UPDATE activity_session SET status='CANCELLED' WHERE id=?",session);
        refunds.processCancelledSession(pending);refunds.processCancelledSession(paid);
        refunds.processCancelledSession(pending);refunds.processCancelledSession(paid);
        assertThat(quotas.occupied(user,session)).isZero();consistent();
    }
    @Test void transactionFailureRollsBackQuotaInventoryAndOrderBeforeSafeRecovery() {
        long id=accept(user,sku,2,"rollback");var old=stocks.find(id);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("隔离明细故障")).when(orderMapper).insertItem(any(),any(),anyInt());
        assertThatThrownBy(()->worker.process(old.id())).isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThat(quotas.occupied(user,session)).isZero();assertThat(requests.find(id)).isNull();
        reset(orderMapper);
        assertThat(quotas.occupied(user,session)).isZero();worker.process(old.id());
        assertThat(quotas.occupied(user,session)).isEqualTo(2);consistent();
    }
    @Test void quotaUnderflowRollsBackCancellationAndNeverClampsCorruptStateToZero() {
        long id=order(user,sku,1);db.update("UPDATE user_session_quota SET occupied_quantity=0 WHERE user_id=? AND session_id=?",user,session);
        assertThatThrownBy(()->orders.cancel(user,id)).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT status FROM ticket_order WHERE id=?",String.class,id)).isEqualTo("PENDING");
        assertThat(db.queryForObject("SELECT locked_stock FROM ticket_sku WHERE id=?",Integer.class,sku)).isEqualTo(1);
        assertThat(quotas.occupied(user,session)).isZero(); // 人造损坏原样保留到隔离测试库回收，不修正为假成功。
    }
    @Test void realMysqlDeadlockRetriesReservationWithoutDoubleOccupancy()throws Exception {
        // 仅测试Spy故意反向锁第二票档，制造数据库1213；生产协议没有这条反向锁路径。
        var fillers=new ArrayList<Long>();for(int i=0;i<20;i++)fillers.add(sku(session,100));long other=fillers.getFirst();
        long request=accept(user,sku,1,"real-deadlock");var claim=stocks.find(request);
        var itemInsertAttempts=new AtomicInteger();
        var blockerReady=new CountDownLatch(1);var workerEntered=new CountDownLatch(1);var blockerTrying=new CountDownLatch(1);
        doAnswer(call->{itemInsertAttempts.incrementAndGet();workerEntered.countDown();await(blockerReady);await(blockerTrying);
            db.queryForObject("SELECT id FROM ticket_sku WHERE id=? FOR UPDATE",Long.class,other);
            return mybatis.getMapper(OrderMapper.class).insertItem(call.getArgument(0),call.getArgument(1),call.getArgument(2));}).when(orderMapper).insertItem(any(),any(),anyInt());
        var pool=Executors.newFixedThreadPool(2);
        try {
            var blocker=pool.submit(()->tx().executeWithoutResult(s->{
                // 大事务使MySQL选择仅修改少量行的购票事务为死锁受害者。
                for(long id:fillers)db.update("UPDATE ticket_sku SET version=version+1 WHERE id=?",id);
                blockerReady.countDown();await(workerEntered);blockerTrying.countDown();
                db.queryForObject("SELECT id FROM ticket_sku WHERE id=? FOR UPDATE",Long.class,sku);
            }));
            assertThat(blockerReady.await(5,TimeUnit.SECONDS)).isTrue();
            var purchase=pool.submit(()->worker.process(claim.id()));
            purchase.get(10,TimeUnit.SECONDS);
            blocker.get(10,TimeUnit.SECONDS);reset(orderMapper);
            assertThat(itemInsertAttempts.get()).isGreaterThanOrEqualTo(2); // 首次真实死锁回滚后，在新事务内重试。
            assertThat(requests.find(request).status()).isEqualTo("SUCCEEDED");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM ticket_order WHERE user_id=?",Integer.class,user)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT locked_stock FROM ticket_sku WHERE id=?",Integer.class,sku)).isEqualTo(1);
            assertThat(quotas.occupied(user,session)).isEqualTo(1);consistent();
        } finally {blockerReady.countDown();blockerTrying.countDown();workerEntered.countDown();pool.shutdownNow();}
    }
    @Test void realMysqlLockTimeoutRollsBackAndTheSameClaimCanRecover()throws Exception {
        requireFaultGuard();
        long request=accept(user,sku,1,"lock-timeout");var claim=stocks.find(request);
        tx().executeWithoutResult(s->resources.lockReservation(user,sku,true));
        var ready=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var blocker=pool.submit(()->tx().executeWithoutResult(s->{resources.lockReservation(user,sku,true);ready.countDown();await(release);}));
            try {
                assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->tx().executeWithoutResult(s->{
                    int original=db.queryForObject("SELECT @@session.innodb_lock_wait_timeout",Integer.class);
                    db.execute("SET SESSION innodb_lock_wait_timeout=1");
                    try {worker.process(claim.id());}
                    finally {db.execute("SET SESSION innodb_lock_wait_timeout="+original);}
                })).isInstanceOf(org.springframework.dao.ConcurrencyFailureException.class);
            } finally {release.countDown();blocker.get(10,TimeUnit.SECONDS);}
        }
        assertThat(requests.find(request)).isNull();
        assertThat(quotas.occupied(user,session)).isZero();
        assertThat(db.queryForObject("SELECT locked_stock FROM ticket_sku WHERE id=?",Integer.class,sku)).isZero();
        worker.process(claim.id());worker.process(request);
        assertThat(requests.find(request).status()).isEqualTo("SUCCEEDED");
        assertThat(quotas.occupied(user,session)).isEqualTo(1);consistent();
    }

    @Test void realPoolExhaustionCreatesNoPartialOrderAndRecoveryCommitsExactlyOnce()throws Exception {
        requireFaultGuard();
        long request=accept(user,sku,1,"pool-exhaustion");var claim=stocks.find(request);
        var pool=dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class);
        int originalMaximum=pool.getMaximumPoolSize(),originalMinimum=pool.getMinimumIdle();
        // 整轮回归缓存多个上下文；只耗尽当前已建连接，避免夹具本身抢占MySQL全局连接名额。
        int existing=pool.getHikariPoolMXBean().getTotalConnections();
        var held=new ArrayList<java.sql.Connection>();
        try {
            pool.setMinimumIdle(0);
            pool.setMaximumPoolSize(existing);
            for(int index=0;index<existing;index++)held.add(pool.getConnection());
            assertThatThrownBy(()->worker.process(claim.id())).hasRootCauseInstanceOf(java.sql.SQLTransientConnectionException.class);
        } finally {
            for(var connection:held)connection.close();
            pool.setMaximumPoolSize(originalMaximum);
            pool.setMinimumIdle(originalMinimum);
        }
        assertThat(requests.find(request)).isNull();
        assertThat(quotas.occupied(user,session)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ticket_order WHERE user_id=?",Integer.class,user)).isZero();
        worker.process(claim.id());worker.process(request);worker.process(request);
        assertThat(requests.find(request).status()).isEqualTo("SUCCEEDED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ticket_order WHERE user_id=?",Integer.class,user)).isEqualTo(1);
        assertThat(quotas.occupied(user,session)).isEqualTo(1);consistent();
    }

    private void requireFaultGuard() {
        assertThat(System.getenv("MYSQL_HOST")).isEqualTo("127.0.0.1");
        assertThat(System.getenv("MYSQL_PORT")).isEqualTo("13307");
        assertThat(db.queryForObject("SELECT token FROM performance_control.environment_guard",String.class)).isEqualTo(System.getenv("RECOVERY_TEST_GUARD"));
    }

    @Test void payAndCancelRaceHasOneTerminalAndOneQuotaEffect()throws Exception {
        long id=order(user,sku,1);
        parallel(2,index->{try{if(index==0)orders.pay(user,id);else orders.cancel(user,id);}catch(BusinessException failure){assertThat(failure.getStatus().value()).isEqualTo(409);}return true;});
        String status=db.queryForObject("SELECT status FROM ticket_order WHERE id=?",String.class,id);
        assertThat(status).isIn("PAID","CANCELLED");assertThat(quotas.occupied(user,session)).isEqualTo(status.equals("PAID")?1:0);consistent();
    }
    @Test void realManagerCannotChangeSalesConfigurationUntilConsumerCommitsAndNextOrderUsesNewPrice()throws Exception {
        long request=accept(user,sku,1,"old-price");var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{entered.countDown();await(release);return mybatis.getMapper(OrderMapper.class).insertItem(call.getArgument(0),call.getArgument(1),call.getArgument(2));}).when(orderMapper).insertItem(any(),any(),anyInt());
        var pool=Executors.newFixedThreadPool(2);var managing=new CountDownLatch(1);
        try {
            var purchase=pool.submit(()->worker.process(request));assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            var change=pool.submit(()->{
                var principal=identities.loadUserByUsername(db.queryForObject("SELECT username FROM user_account WHERE id=?",String.class,admin));
                org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                    new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities()));
                try {
                    managing.countDown();int version=db.queryForObject("SELECT version FROM ticket_sku WHERE id=?",Integer.class,sku);
                    // 管理线程等待场次锁；库存流水不改变配置版本，管理编辑仍使用乐观锁。
                    try { ticketManagement.offline(sku,new com.ticketing.dto.VersionRequest(version)); }
                    catch(BusinessException conflict){assertThat(conflict.getStatus().value()).isEqualTo(409);}
                    version=db.queryForObject("SELECT version FROM ticket_sku WHERE id=?",Integer.class,sku);
                    if(db.queryForObject("SELECT status FROM ticket_sku WHERE id=?",String.class,sku).equals("ON_SALE"))
                        ticketManagement.offline(sku,new com.ticketing.dto.VersionRequest(version));
                    var values=db.queryForMap("SELECT name,sale_starts_at,sale_ends_at,version FROM ticket_sku WHERE id=?",sku);
                    var from=db.queryForObject("SELECT sale_starts_at FROM ticket_sku WHERE id=?",java.time.LocalDateTime.class,sku).atOffset(java.time.ZoneOffset.UTC);
                    var to=db.queryForObject("SELECT sale_ends_at FROM ticket_sku WHERE id=?",java.time.LocalDateTime.class,sku).atOffset(java.time.ZoneOffset.UTC);
                    var updated=ticketManagement.update(sku,new com.ticketing.dto.TicketWriteRequest(values.get("name").toString(),"250",100,from,to,4,((Number)values.get("version")).intValue()));
                    ticketManagement.publish(sku,new com.ticketing.dto.VersionRequest(updated.version()));
                } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
            });
            assertThat(managing.await(1,TimeUnit.SECONDS)).isTrue();Thread.sleep(100);assertThat(change.isDone()).isFalse();
            release.countDown();purchase.get(5,TimeUnit.SECONDS);change.get(5,TimeUnit.SECONDS);reset(orderMapper);
            long original=requests.find(request).orderId();assertThat(db.queryForObject("SELECT total_amount_cents FROM ticket_order WHERE id=?",Long.class,original)).isEqualTo(100);
            long newer=order(user,sku,1);assertThat(db.queryForObject("SELECT total_amount_cents FROM ticket_order WHERE id=?",Long.class,newer)).isEqualTo(250);consistent();
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void waitlistQueueOwnsNoQuotaAndFifoAllocationRefundAndCancellationStayAtomic() {
        db.update("UPDATE ticket_sku SET total_stock=0 WHERE id=?",sku);snapshots.synchronize(sku);long second=user("USER");
        long head=Long.parseLong(waitlist.join(user,new JoinWaitlistRequest(Long.toString(sku),2)).id());
        long next=Long.parseLong(waitlist.join(second,new JoinWaitlistRequest(Long.toString(sku),1)).id());
        assertThat(quotas.occupied(user,session)).isZero();db.update("UPDATE ticket_sku SET total_stock=1 WHERE id=?",sku);snapshots.synchronize(sku);
        assertThat(dispatcher.dispatch(sku)).isZero();
        db.update("UPDATE ticket_sku SET total_stock=2 WHERE id=?",sku);snapshots.synchronize(sku);dispatcher.dispatch(sku);
        long request=db.queryForObject("SELECT purchase_request_id FROM waitlist_entry WHERE id=?",Long.class,head);worker.process(request);
        long headOrder=db.queryForObject("SELECT reserved_order_id FROM waitlist_entry WHERE id=?",Long.class,head);
        orders.pay(user,headOrder);assertThat(quotas.occupied(user,session)).isEqualTo(2);
        refunds.refund(admin,headOrder,RefundReason.CUSTOMER_REQUEST);
        assertThat(db.queryForObject("SELECT status FROM waitlist_entry WHERE id=?",String.class,head)).isEqualTo("COMPLETED");
        dispatcher.dispatch(sku);worker.process(db.queryForObject("SELECT purchase_request_id FROM waitlist_entry WHERE id=?",Long.class,next));
        long nextOrder=db.queryForObject("SELECT reserved_order_id FROM waitlist_entry WHERE id=?",Long.class,next);
        orders.cancel(second,nextOrder);assertThat(quotas.occupied(second,session)).isZero();consistent();
    }
    static void await(CountDownLatch latch){try{if(!latch.await(10,TimeUnit.SECONDS))throw new AssertionError("锁协议屏障超时");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
}
