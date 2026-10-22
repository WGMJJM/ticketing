package com.ticketing.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.dto.RegisterRequest;
import com.ticketing.service.AccountService;
import com.ticketing.service.OrderService;
import com.ticketing.service.OutboxService;
import com.ticketing.model.InventoryChangeReason;
import com.ticketing.config.OutboxProperties;
import com.ticketing.entity.OutboxEvent;
import com.ticketing.mapper.OutboxMapper;
import com.ticketing.mapper.WaitlistMapper;
import com.ticketing.service.WaitlistCompensation;
import com.ticketing.service.WaitlistDispatcher;
import com.ticketing.service.WaitlistService;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/** 真实HTTP身份、独立MySQL事务和独立RabbitMQ测试队列，停止后台轮询以精确驱动竞争。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "server.servlet.session.cookie.secure=false","ticketing.admin-bootstrap.enabled=false",
        "ticketing.order.expiry.enabled=false","ticketing.waitlist.jobs.enabled=false",
        "ticketing.waitlist.messaging.enabled=true","ticketing.waitlist.exchange=ticketing.waitlist.it",
        "ticketing.waitlist.queue=ticketing.waitlist.it.dispatch","spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.simple.retry.max-attempts=3","spring.rabbitmq.listener.simple.retry.initial-interval=100ms",
        "spring.rabbitmq.listener.simple.retry.max-interval=400ms","ticketing.waitlist.outbox.max-attempts=3"})
class WaitlistManagementIT {
    private static final String QUEUE="ticketing.waitlist.it.dispatch";
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountService accounts;
    @Autowired private OrderService orders;
    @Autowired private WaitlistService waitlists;
    @Autowired private OutboxService outbox;
    @Autowired private InventoryEvents writer;
    @Autowired private WaitlistCompensation compensation;
    @Autowired private RabbitTemplate rabbit;
    @Autowired private AmqpAdmin rabbitAdmin;
    @Autowired private Declarables waitlistTopology;
    @Autowired private RabbitListenerEndpointRegistry registry;
    @Autowired private PlatformTransactionManager transactions;
    @MockitoSpyBean private OutboxPublisher publisher;
    @MockitoSpyBean private WaitlistDispatcher dispatcher;
    @MockitoSpyBean private WaitlistMapper waitlistMapper;
    @MockitoSpyBean private OutboxMapper outboxMapper;
    private final List<Long> userIds=new ArrayList<>();
    private long activityId;
    private long sessionId;
    private long skuId;
    private Client a;
    private Client b;
    private Client c;
    private Client operator;

    @BeforeEach
    void fixture() throws Exception {
        registry.stop();
        for (var declarable:waitlistTopology.getDeclarables()) {
            if (declarable instanceof Exchange value) rabbitAdmin.declareExchange(value);
            if (declarable instanceof Queue value) rabbitAdmin.declareQueue(value);
            if (declarable instanceof Binding value) rabbitAdmin.declareBinding(value);
        }
        rabbitAdmin.purgeQueue(QUEUE,false); rabbitAdmin.purgeQueue(QUEUE+".dead",false);
        a=client(false); b=client(false); c=client(false); operator=client(true);
        String title="候补验收"+UUID.randomUUID();
        jdbc.update("INSERT INTO activity(title,category,city,venue,status,published_at) VALUES(?,'CONCERT','上海','测试场馆','PUBLISHED',UTC_TIMESTAMP(6))",title);
        activityId=jdbc.queryForObject("SELECT id FROM activity WHERE title=?",Long.class,title);
        jdbc.update("INSERT INTO activity_session(activity_id,title,starts_at,ends_at,sale_starts_at) VALUES(?,'候补测试',DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 DAY),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 2 DAY),DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 DAY))",activityId);
        sessionId=jdbc.queryForObject("SELECT id FROM activity_session WHERE activity_id=?",Long.class,activityId);
        skuId=sku();
    }
    @AfterEach
    void cleanup() {
        registry.stop();
        reset(publisher,dispatcher,waitlistMapper,outboxMapper);
        rabbitAdmin.purgeQueue(QUEUE,false); rabbitAdmin.purgeQueue(QUEUE+".dead",false);
        if (sessionId!=0) {
            jdbc.update("DELETE e FROM outbox_event e JOIN ticket_sku s ON e.aggregate_id=s.id WHERE e.aggregate_type='TICKET_SKU' AND s.session_id=?",sessionId);
            jdbc.update("DELETE w FROM waitlist_entry w JOIN ticket_sku s ON w.sku_id=s.id WHERE s.session_id=?",sessionId);
            jdbc.update("DELETE t FROM electronic_ticket t JOIN ticket_order o ON t.order_id=o.id WHERE o.session_id=?",sessionId);
            jdbc.update("DELETE p FROM payment_record p JOIN ticket_order o ON p.order_id=o.id WHERE o.session_id=?",sessionId);
            jdbc.update("DELETE i FROM ticket_order_item i JOIN ticket_order o ON i.order_id=o.id WHERE o.session_id=?",sessionId);
            jdbc.update("DELETE FROM ticket_order WHERE session_id=?",sessionId);
            jdbc.update("DELETE FROM ticket_sku WHERE session_id=?",sessionId);
            jdbc.update("DELETE FROM activity_session WHERE id=?",sessionId);
        }
        if (activityId!=0) jdbc.update("DELETE FROM activity WHERE id=?",activityId);
        for (long id:userIds) jdbc.update("DELETE FROM user_account WHERE id=?",id);
    }

    @Test
    void joinsAndCancelsRealWaitlistWithoutReservingInventory() throws Exception {
        JsonNode entry=a.join(skuId,2,200);
        assertThat(entry.path("status").asText()).isEqualTo("WAITING");
        assertThat(entry.path("id").isTextual()).isTrue();
        assertThat(entry.path("skuId").asText()).isEqualTo(Long.toString(skuId));
        assertThat(a.call("GET","/waitlist",null,200).path("data").path("total").asInt()).isEqualTo(1);
        assertThat(b.call("GET","/waitlist",null,200).path("data").path("total").asInt()).isZero();
        assertThat(jdbc.queryForObject("SELECT expires_at<=sale_ends_at FROM waitlist_entry w JOIN ticket_sku s ON w.sku_id=s.id WHERE w.id=?",Boolean.class,entry.path("id").asLong())).isTrue();
        a.call("POST","/waitlist/"+entry.path("id").asText()+"/cancel",null,200);
        a.call("POST","/waitlist/"+entry.path("id").asText()+"/cancel",null,200);
        assertThat(state(entry)).isEqualTo("CANCELLED");
        invariant(0,0); assertThat(orderCount()).isZero();
    }
    @Test
    void concurrentDuplicateRequestsReturnOneActiveEntryAndDifferentQuantityConflicts() throws Exception {
        var ids=parallel(6,index->a.join(skuId,2,200).path("id").asText());
        assertThat(ids.stream().distinct().count()).isEqualTo(1);
        a.join(skuId,1,409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM waitlist_entry WHERE sku_id=? AND active_marker=1",Integer.class,skuId)).isEqualTo(1);
        assertThat(eventCount()).isEqualTo(1);
        invariant(0,0);
    }
    @Test
    void validatesInventoryQuantityWindowAndReadonlyIdentityFields() throws Exception {
        a.call("POST","/waitlist",Map.of("skuId",Long.toString(skuId),"quantity",0),400);
        a.call("POST","/waitlist",Map.of("skuId",Long.toString(skuId),"quantity",1.5),400);
        a.call("POST","/waitlist",Map.of("skuId",Long.toString(skuId),"quantity",1,"userId",b.id),400);
        a.call("POST","/waitlist",Map.of("skuId","9999999999999999999","quantity",1),400);
        a.join(skuId,5,409);
        stock(3); a.join(skuId,3,409);
        a.join(skuId,4,200); // 部分库存不足也可候补。
        jdbc.update("UPDATE ticket_sku SET sale_starts_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 10 MINUTE) WHERE id=?",skuId);
        b.join(skuId,1,409);
        assertThat(orderCount()).isZero(); invariant(0,0);
    }
    @Test
    void ownershipAuthenticationCsrfAndDisabledSessionAreEnforced() throws Exception {
        JsonNode entry=a.join(skuId,1,200); String path="/waitlist/"+entry.path("id").asText();
        for (Client other:List.of(b,operator)) { other.call("GET",path,null,404); other.call("POST",path+"/cancel",null,404); }
        Client anonymous=new Client(); anonymous.csrf(); anonymous.call("GET","/waitlist",null,401); anonymous.join(skuId,1,401);
        String token=a.token; a.token=""; a.join(skuId,1,403); a.token="wrong"; a.call("POST",path+"/cancel",null,403); a.token=token;
        jdbc.update("UPDATE user_account SET status='DISABLED' WHERE id=?",a.id);
        a.call("GET",path,null,401);
        assertThat(state(entry)).isEqualTo("WAITING"); invariant(0,0);
    }
    @Test
    void fifoAllocatesAbcWithServerPricesDomainAndSharedLimits() throws Exception {
        var entries=List.of(a.join(skuId,1,200),b.join(skuId,2,200),c.join(skuId,1,200));
        stock(4); assertThat(dispatcher.dispatch(skuId)).isEqualTo(3);
        long previous=0;
        for (JsonNode entry:entries) {
            assertThat(state(entry)).isEqualTo("ALLOCATED");
            long order=reserved(entry); assertThat(order).isGreaterThan(previous); previous=order;
            var row=jdbc.queryForMap("SELECT o.*,i.sku_id,i.quantity,i.unit_price_cents FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id WHERE o.id=?",order);
            assertThat(row.get("idempotency_operation")).isEqualTo("WAITLIST_CONFIRM");
            assertThat(row.get("idempotency_key")).isEqualTo("waitlist-"+entry.path("id").asText());
            assertThat(((Number)row.get("total_amount_cents")).longValue()).isEqualTo(entry.path("quantity").asInt()*12345L);
            assertThat(((Number)row.get("sku_id")).longValue()).isEqualTo(skuId);
        }
        invariant(4,0); assertThat(orderCount()).isEqualTo(3);
    }
    @Test
    void insufficientStockNeverSkipsHeadAndCancellationAllowsNext() throws Exception {
        JsonNode head=a.join(skuId,4,200); JsonNode next=b.join(skuId,1,200);
        stock(2); assertThat(dispatcher.dispatch(skuId)).isZero();
        assertThat(state(head)).isEqualTo("WAITING"); assertThat(state(next)).isEqualTo("WAITING"); assertThat(orderCount()).isZero();
        a.call("POST","/waitlist/"+head.path("id").asText()+"/cancel",null,200);
        assertThat(dispatcher.dispatch(skuId)).isEqualTo(1); assertThat(state(next)).isEqualTo("ALLOCATED"); invariant(1,0);
    }
    @Test
    void concurrentEnqueuesAndDispatchersPreserveIdOrderAndReserveOnce() throws Exception {
        var actors=List.of(a,b,c);
        var entries=parallel(3,index->actors.get(index).join(skuId,1,200));
        stock(3);
        parallel(6,index->dispatcher.dispatch(skuId));
        var rows=jdbc.queryForList("SELECT id,reserved_order_id FROM waitlist_entry WHERE sku_id=? ORDER BY id",skuId);
        assertThat(rows).hasSize(3);
        long previous=0;
        for (var row:rows) { long order=((Number)row.get("reserved_order_id")).longValue(); assertThat(order).isGreaterThan(previous); previous=order; }
        assertThat(orderCount()).isEqualTo(3); invariant(3,0);
        assertThat(dispatcher.dispatch(skuId)).isZero();
        assertThat(entries).hasSize(3);
    }
    @Test
    void staleHeadCandidateIsRecheckedAfterAcquiringLocks() throws Exception {
        JsonNode first=a.join(skuId,1,200); JsonNode next=b.join(skuId,1,200);
        stock(1); a.call("POST","/waitlist/"+first.path("id").asText()+"/cancel",null,200);
        assertThat(waitlists.allocateHead(skuId,first.path("id").asLong())).isEqualTo(WaitlistService.Allocation.RETRY);
        assertThat(state(next)).isEqualTo("WAITING"); assertThat(orderCount()).isZero();
        dispatcher.dispatch(skuId); assertThat(state(next)).isEqualTo("ALLOCATED"); invariant(1,0);
    }
    @Test
    void invalidHeadExpiresThenNextCanAllocateAndQuotaIsRechecked() throws Exception {
        JsonNode first=a.join(skuId,2,200); JsonNode next=b.join(skuId,1,200);
        long otherSku=sku(); jdbc.update("UPDATE ticket_sku SET total_stock=4 WHERE id=?",otherSku);
        a.create(otherSku,4);
        stock(1); dispatcher.dispatch(skuId);
        assertThat(state(first)).isEqualTo("EXPIRED"); assertThat(state(next)).isEqualTo("ALLOCATED");
        assertThat(used(a)).isEqualTo(4); assertThat(used(b)).isEqualTo(1); invariant(1,0);
    }
    @Test
    void disabledExpiredOrOfflineHeadsReachTerminalStateWithoutInventoryLeaks() throws Exception {
        JsonNode disabled=a.join(skuId,1,200); JsonNode expired=b.join(skuId,1,200); JsonNode live=c.join(skuId,1,200);
        jdbc.update("UPDATE user_account SET status='DISABLED' WHERE id=?",a.id);
        jdbc.update("UPDATE waitlist_entry SET created_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 2 HOUR),expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?",expired.path("id").asLong());
        stock(1); dispatcher.dispatch(skuId);
        assertThat(state(disabled)).isEqualTo("EXPIRED"); assertThat(state(expired)).isEqualTo("EXPIRED"); assertThat(state(live)).isEqualTo("ALLOCATED"); invariant(1,0);
        long second=sku(); JsonNode closed=b.join(second,1,200);
        long third=sku(); JsonNode cancelledSession=b.join(third,1,200);
        jdbc.update("UPDATE activity SET status='OFFLINE' WHERE id=?",activityId);
        dispatcher.dispatch(second); assertThat(state(closed)).isEqualTo("EXPIRED");
        jdbc.update("UPDATE activity SET status='PUBLISHED' WHERE id=?",activityId);
        jdbc.update("UPDATE activity_session SET status='CANCELLED' WHERE id=?",sessionId);
        dispatcher.dispatch(third); assertThat(state(cancelledSession)).isEqualTo("EXPIRED");
    }
    @Test
    void allocationFailureRollsBackOrderInventoryAndWaitlistThenCanRetry() throws Exception {
        JsonNode entry=a.join(skuId,2,200); stock(2);
        doReturn(0).when(waitlistMapper).allocate(anyLong(),anyLong());
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(()->dispatcher.dispatch(skuId)).isInstanceOf(RuntimeException.class);
            assertThat(state(entry)).isEqualTo("WAITING"); invariant(0,0); assertThat(orderCount()).isZero(); assertThat(used(a)).isZero();
        } finally { reset(waitlistMapper); }
        dispatcher.dispatch(skuId); assertThat(state(entry)).isEqualTo("ALLOCATED"); invariant(2,0); assertThat(orderCount()).isEqualTo(1);
    }
    @Test
    void allocatedWaitlistCannotCancelDirectlyAndOrderCancelOrExpirySynchronizes() throws Exception {
        JsonNode first=a.join(skuId,2,200); JsonNode next=b.join(skuId,2,200); stock(2); dispatcher.dispatch(skuId);
        a.call("POST","/waitlist/"+first.path("id").asText()+"/cancel",null,409); invariant(2,0);
        a.call("POST","/orders/"+reserved(first)+"/cancel",null,200);
        assertThat(state(first)).isEqualTo("EXPIRED"); invariant(0,0); assertThat(used(a)).isZero();
        dispatcher.dispatch(skuId); assertThat(state(next)).isEqualTo("ALLOCATED");
        long order=reserved(next);
        jdbc.update("UPDATE user_account SET status='DISABLED' WHERE id=?",b.id);
        jdbc.update("UPDATE ticket_order SET created_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 20 MINUTE),expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?",order);
        assertThat(orders.expire(b.id,order)).isTrue(); assertThat(orders.expire(b.id,order)).isFalse();
        assertThat(state(next)).isEqualTo("EXPIRED"); invariant(0,0); assertThat(used(b)).isZero();
    }
    @Test
    void reservedOrderPaymentCompletesWaitlistExactlyOnce() throws Exception {
        JsonNode entry=a.join(skuId,2,200); stock(2); dispatcher.dispatch(skuId); long order=reserved(entry);
        var results=parallel(5,index->a.call("POST","/orders/"+order+"/pay",null,200).path("data").path("paymentNo").asText());
        assertThat(results.stream().distinct().count()).isEqualTo(1); assertThat(state(entry)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_record WHERE order_id=?",Integer.class,order)).isEqualTo(1);
        assertThat(a.call("GET","/waitlist/"+entry.path("id").asText(),null,200).path("data").path("orderStatus").asText()).isEqualTo("PAID");
        invariant(0,2); assertThat(used(a)).isEqualTo(2);
    }

    @Test
    void cancellationOutboxSurvivesConsumerInterruptionAndCompletesRealPayment() throws Exception {
        stock(1); JsonNode held=b.create(skuId,1); JsonNode entry=a.join(skuId,1,200); clearEvents();
        b.call("POST","/orders/"+held.path("id").asText()+"/cancel",null,200);
        assertThat(eventCount()).isEqualTo(1); invariant(0,0);
        publisher.publishBatch();
        // 测试共用开发库，发布器可能一并投递其他业务遗留的事件；不以队列总数代替本单事实。
        assertThat(eventStatus()).isEqualTo("SENT"); assertThat(depth(QUEUE)).isGreaterThanOrEqualTo(1);
        assertThat(state(entry)).isEqualTo("WAITING"); // 消费者停止期间消息留在真实持久队列。
        registry.start(); await(()->"ALLOCATED".equals(state(entry)));
        a.call("POST","/orders/"+reserved(entry)+"/pay",null,200);
        assertThat(state(entry)).isEqualTo("COMPLETED"); invariant(0,1);
    }
    @Test
    void duplicateRealRabbitMessagesAndTwoConsumersAllocateEachEntryOnce() throws Exception {
        var entries=List.of(a.join(skuId,1,200),b.join(skuId,1,200),c.join(skuId,1,200)); stock(3); clearEvents(); appendEvent();
        publisher.publishBatch();
        OutboxEvent event=event();
        for (int count=0;count<6;count++) sendDuplicate(event);
        registry.start(); await(()->entries.stream().allMatch(entry->"ALLOCATED".equals(state(entry))));
        await(()->depth(QUEUE)==0);
        assertThat(orderCount()).isEqualTo(3); invariant(3,0);
        assertThat(reserved(entries.get(0))).isLessThan(reserved(entries.get(1)));
        assertThat(reserved(entries.get(1))).isLessThan(reserved(entries.get(2)));
    }
    @Test
    void brokerFailureBacksOffThenRealPublisherRecovers() throws Exception {
        JsonNode entry=a.join(skuId,1,200); stock(1); clearEvents(); appendEvent();
        CachingConnectionFactory unavailable=new CachingConnectionFactory("127.0.0.1",1);
        try {
            unavailable.setConnectionTimeout(200);
            new OutboxPublisher(outbox,new RabbitTemplate(unavailable),"ticketing.waitlist.it",new OutboxProperties(1,100,30,3,2,300)).publishBatch();
        } finally { unavailable.destroy(); }
        assertThat(eventStatus()).isEqualTo("PENDING"); assertThat(event().getRetryCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT next_retry_at>UTC_TIMESTAMP(6) FROM outbox_event WHERE id=?",Boolean.class,event().getId())).isTrue();
        assertThat(depth(QUEUE)).isZero();
        dueEvent(); publisher.publishBatch(); assertThat(eventStatus()).isEqualTo("SENT");
        registry.start(); await(()->"ALLOCATED".equals(state(entry))); invariant(1,0);
    }
    @Test
    void lostPublisherConfirmationRedeliversWithoutCreatingAnotherReservedOrder() throws Exception {
        JsonNode entry=a.join(skuId,1,200); stock(1); clearEvents(); appendEvent();
        doThrow(new TimeoutException("验收模拟确认丢失")).when(publisher).awaitConfirm(any());
        publisher.publishBatch();
        assertThat(eventStatus()).isEqualTo("PENDING");
        registry.start(); await(()->"ALLOCATED".equals(state(entry)));
        reset(publisher); dueEvent(); publisher.publishBatch();
        assertThat(eventStatus()).isEqualTo("SENT"); await(()->depth(QUEUE)==0);
        assertThat(orderCount()).isEqualTo(1); invariant(1,0);
    }
    @Test
    void expiredClaimAndFreshPublisherRecoverWithFencingAgainstOldWorker() throws Exception {
        JsonNode entry=a.join(skuId,1,200); stock(1); clearEvents(); appendEvent();
        OutboxEvent old=outbox.claimNext(); assertThat(old.getRetryCount()).isEqualTo(1);
        dueEvent(); OutboxEvent newer=outbox.claimNext(); assertThat(newer.getRetryCount()).isEqualTo(2);
        assertThat(outbox.sent(old)).isFalse(); outbox.failed(old);
        assertThat(eventStatus()).isEqualTo("PROCESSING");
        dueEvent(); new OutboxPublisher(outbox,rabbit,"ticketing.waitlist.it",new OutboxProperties(1,5000,30,3,2,300)).publishBatch();
        assertThat(eventStatus()).isEqualTo("SENT"); assertThat(event().getRetryCount()).isEqualTo(3);
        registry.start(); await(()->"ALLOCATED".equals(state(entry))); invariant(1,0); assertThat(orderCount()).isEqualTo(1);
    }
    @Test
    void concurrentPublishClaimsAreExclusiveAcrossDatabaseConnections() throws Exception {
        appendEvent();
        var claims=parallel(6,index->outbox.claimNext());
        assertThat(claims.stream().filter(java.util.Objects::nonNull).count()).isEqualTo(1);
        assertThat(eventStatus()).isEqualTo("PROCESSING"); assertThat(event().getRetryCount()).isEqualTo(1);
    }
    @Test
    void exhaustedOutboxRetriesBecomeFailedButDatabaseCompensationStillAllocates() throws Exception {
        JsonNode entry=a.join(skuId,1,200); stock(1); clearEvents(); appendEvent();
        CachingConnectionFactory unavailable=new CachingConnectionFactory("127.0.0.1",1);
        try {
            unavailable.setConnectionTimeout(200);
            OutboxPublisher failed=new OutboxPublisher(outbox,new RabbitTemplate(unavailable),"ticketing.waitlist.it",new OutboxProperties(1,100,30,3,2,300));
            for (int count=0;count<3;count++) { dueEvent(); failed.publishBatch(); }
        } finally { unavailable.destroy(); }
        assertThat(eventStatus()).isEqualTo("FAILED"); assertThat(event().getRetryCount()).isEqualTo(3);
        assertThat(state(entry)).isEqualTo("WAITING");
        compensation.scan(); assertThat(state(entry)).isEqualTo("ALLOCATED"); invariant(1,0);
    }
    @Test
    void consumerTransientFailureRetriesWithBackoffAndEventuallyCommits() throws Exception {
        JsonNode entry=a.join(skuId,1,200); stock(1); clearEvents(); appendEvent();
        doThrow(new IllegalStateException("验收模拟临时消费故障")).doCallRealMethod().when(dispatcher).dispatch(skuId);
        publisher.publishBatch(); registry.start(); await(()->"ALLOCATED".equals(state(entry)));
        assertThat(depth(QUEUE+".dead")).isZero(); assertThat(orderCount()).isEqualTo(1); invariant(1,0);
    }
    @Test
    void persistentConsumerFailureGoesToDeadLetterAndCompensationRestoresBusiness() throws Exception {
        JsonNode entry=a.join(skuId,1,200); stock(1); clearEvents(); appendEvent();
        doThrow(new IllegalStateException("验收模拟持续消费故障")).when(dispatcher).dispatch(skuId);
        publisher.publishBatch(); registry.start(); await(()->depth(QUEUE+".dead")==1);
        assertThat(state(entry)).isEqualTo("WAITING"); assertThat(orderCount()).isZero(); invariant(0,0);
        registry.stop(); reset(dispatcher); compensation.scan();
        assertThat(state(entry)).isEqualTo("ALLOCATED"); invariant(1,0);
    }
    @Test
    void stockChangesAndOutboxInsertionRollbackTogetherOnWriterFailure() throws Exception {
        stock(1); JsonNode held=b.create(skuId,1); clearEvents();
        doThrow(new DataIntegrityViolationException("验收注入事件写入失败")).when(outboxMapper).insert(any(OutboxEvent.class));
        try {
            b.call("POST","/orders/"+held.path("id").asText()+"/cancel",null,409);
            invariant(1,0); assertThat(eventCount()).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM ticket_order WHERE id=?",String.class,held.path("id").asLong())).isEqualTo("PENDING");
            var row=jdbc.queryForMap("SELECT * FROM ticket_sku WHERE id=?",skuId);
            operator.call("PUT","/admin/tickets/"+skuId,ticketBody(row,2),409);
            assertThat(jdbc.queryForObject("SELECT total_stock FROM ticket_sku WHERE id=?",Integer.class,skuId)).isEqualTo(1);
        } finally { reset(outboxMapper); }
        b.call("POST","/orders/"+held.path("id").asText()+"/cancel",null,200); invariant(0,0); assertThat(eventCount()).isEqualTo(1);
    }
    @Test
    void wrongSkuIsRejectedByForeignKeyAndQuantityMismatchRollsBackPayment() throws Exception {
        JsonNode entry=a.join(skuId,1,200); stock(1); dispatcher.dispatch(skuId); long order=reserved(entry); long different=sku();
        assertThatThrownBy(() -> jdbc.update("UPDATE waitlist_entry SET sku_id=? WHERE id=?", different, entry.path("id").asLong()))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 数量一致仍由同步候补协作校验，验证状态、库存、流水在异常时全部回滚。
        jdbc.update("UPDATE waitlist_entry SET quantity=2 WHERE id=?", entry.path("id").asLong());
        a.call("POST","/orders/"+order+"/pay",null,409);
        a.call("GET","/waitlist/"+entry.path("id").asText(),null,409);
        assertThat(jdbc.queryForObject("SELECT status FROM ticket_order WHERE id=?",String.class,order)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_record WHERE order_id=?",Integer.class,order)).isZero(); invariant(1,0);
    }
    @Test
    void normalPurchaseAndWaitlistRacingCancellationPaymentAndExpiryConserveStock() throws Exception {
        stock(1); JsonNode held=b.create(skuId,1); JsonNode entry=a.join(skuId,1,200);
        var results=parallel(3,index->{
            if (index==0) return b.call("POST","/orders/"+held.path("id").asText()+"/cancel",null,200).path("code").asText();
            if (index==1) return Integer.toString(dispatcher.dispatch(skuId));
            JsonNode result=c.raw("POST","/orders",json.writeValueAsString(Map.of("skuId",Long.toString(skuId),"quantity",1)),"application/json",UUID.randomUUID().toString(),-1);
            assertThat(result.path("httpStatus").asInt()).isIn(200,409);
            return result.path("httpStatus").asInt()==200?result.path("data").path("id").asText():"-";
        });
        compensation.scan();
        Long allocated=jdbc.queryForObject("SELECT reserved_order_id FROM waitlist_entry WHERE id=?",Long.class,entry.path("id").asLong());
        Client winner=allocated==null?c:a;
        long order=allocated==null?Long.parseLong(results.get(2)):allocated;
        invariant(1,0); assertThat(orderCount()).isEqualTo(2);
        parallel(3,index->{
            if (index==0) return winner.call("POST","/orders/"+order+"/pay",null,200).path("code").asText();
            if (index==1) return Boolean.toString(orders.expire(winner.id,order));
            return b.call("POST","/orders/"+held.path("id").asText()+"/cancel",null,200).path("code").asText();
        });
        invariant(0,1); assertThat(used(winner)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_record WHERE order_id=?",Integer.class,order)).isEqualTo(1);
    }

    private void clearEvents() { jdbc.update("DELETE FROM outbox_event WHERE aggregate_type='TICKET_SKU' AND aggregate_id=?",skuId); }
    private void appendEvent() { new TransactionTemplate(transactions).executeWithoutResult(status->writer.append(skuId,InventoryChangeReason.STOCK_INCREASED)); }
    private OutboxEvent event() { return outboxMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OutboxEvent>().eq(OutboxEvent::getAggregateId,skuId).eq(OutboxEvent::getAggregateType,"TICKET_SKU")); }
    private String eventStatus() { return event().getStatus(); }
    private void dueEvent() { jdbc.update("UPDATE outbox_event SET next_retry_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE aggregate_type='TICKET_SKU' AND aggregate_id=?",skuId); }
    private int depth(String queue) { var properties=rabbitAdmin.getQueueProperties(queue); return properties==null?0:((Number)properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).intValue(); }
    private void sendDuplicate(OutboxEvent event) {
        MessageProperties properties=new MessageProperties(); properties.setMessageId(event.getEventId()); properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send("ticketing.waitlist.it","inventory.available",new Message(event.getPayload().getBytes(StandardCharsets.UTF_8),properties));
    }

    private long sku() {
        String name="候补票"+UUID.randomUUID();
        jdbc.update("INSERT INTO ticket_sku(session_id,name,price_cents,total_stock,sale_starts_at,sale_ends_at,status) VALUES(?,?,12345,0,DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 HOUR),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR),'ON_SALE')",sessionId,name);
        return jdbc.queryForObject("SELECT id FROM ticket_sku WHERE name=? AND session_id=?",Long.class,name,sessionId);
    }
    private void stock(int total) throws Exception {
        var row=jdbc.queryForMap("SELECT * FROM ticket_sku WHERE id=?",skuId);
        operator.call("PUT","/admin/tickets/"+skuId,ticketBody(row,total),200);
    }
    private Map<String,Object> ticketBody(Map<String,Object> row,int total) {
        return Map.of("name",row.get("name"),"priceCents",row.get("price_cents").toString(),"totalStock",total,
                "saleStartsAt",((LocalDateTime)row.get("sale_starts_at")).toInstant(ZoneOffset.UTC).toString(),
                "saleEndsAt",((LocalDateTime)row.get("sale_ends_at")).toInstant(ZoneOffset.UTC).toString(),
                "maxQuantityPerOrder",4,"version",row.get("version"));
    }
    private String state(JsonNode entry) { return jdbc.queryForObject("SELECT status FROM waitlist_entry WHERE id=?",String.class,entry.path("id").asLong()); }
    private long reserved(JsonNode entry) { return jdbc.queryForObject("SELECT reserved_order_id FROM waitlist_entry WHERE id=?",Long.class,entry.path("id").asLong()); }
    private int orderCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order WHERE session_id=?",Integer.class,sessionId); }
    private int eventCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_type='TICKET_SKU' AND aggregate_id=?",Integer.class,skuId); }
    private int used(Client actor) { return jdbc.queryForObject("SELECT COALESCE(SUM(i.quantity),0) FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id WHERE o.user_id=? AND o.session_id=? AND o.status IN('PENDING','PAID')",Integer.class,actor.id,sessionId); }
    private void invariant(int locked,int sold) {
        var row=jdbc.queryForMap("SELECT * FROM ticket_sku WHERE id=?",skuId);
        assertThat(((Number)row.get("locked_stock")).intValue()).isEqualTo(locked);
        assertThat(((Number)row.get("sold_stock")).intValue()).isEqualTo(sold);
        assertThat(locked).isGreaterThanOrEqualTo(0); assertThat(sold).isGreaterThanOrEqualTo(0);
        assertThat(locked+sold).isLessThanOrEqualTo(((Number)row.get("total_stock")).intValue());
    }
    private Client client(boolean administrator) throws Exception {
        Client client=new Client(); String username="wait_"+UUID.randomUUID().toString().replace("-","").substring(0,24); String password=UUID.randomUUID().toString();
        client.csrf();
        if (administrator) accounts.initializeAdmin(new RegisterRequest(username,password));
        else client.call("POST","/auth/register",Map.of("username",username,"password",password),200);
        client.id=jdbc.queryForObject("SELECT id FROM user_account WHERE username=?",Long.class,username); userIds.add(client.id);
        client.raw("POST","/auth/login","username="+username+"&password="+URLEncoder.encode(password,StandardCharsets.UTF_8),"application/x-www-form-urlencoded",null,200); client.csrf(); return client;
    }
    private <T> List<T> parallel(int count,CheckedFunction<T> action) throws Exception {
        CountDownLatch start=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(count)) {
            List<Future<T>> futures=new ArrayList<>();
            for (int index=0;index<count;index++) { int value=index; futures.add(pool.submit(()-> { start.await(); return action.apply(value); })); }
            start.countDown(); List<T> results=new ArrayList<>(); for (Future<T> future:futures) results.add(future.get(25,TimeUnit.SECONDS)); return results;
        }
    }
    @FunctionalInterface private interface CheckedFunction<T> { T apply(int index) throws Exception; }
    private void await(BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime()<deadline) Thread.sleep(100);
        assertThat(condition.getAsBoolean()).isTrue();
    }
    private class Client {
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        long id; String token="";
        void csrf() throws Exception { token=call("GET","/auth/csrf",null,200).path("data").path("token").asText(); }
        JsonNode join(long sku,int quantity,int expected) throws Exception { return call("POST","/waitlist",Map.of("skuId",Long.toString(sku),"quantity",quantity),expected).path("data"); }
        JsonNode create(long sku,int quantity) throws Exception { return raw("POST","/orders",json.writeValueAsString(Map.of("skuId",Long.toString(sku),"quantity",quantity)),"application/json",UUID.randomUUID().toString(),200).path("data"); }
        JsonNode call(String method,String path,Object body,int expected) throws Exception { return raw(method,path,body==null?"":json.writeValueAsString(body),"application/json",null,expected); }
        JsonNode raw(String method,String path,String body,String type,String key,int expected) throws Exception {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).timeout(Duration.ofSeconds(20)).header("Content-Type",type);
            if (!token.isEmpty()) request.header("X-CSRF-TOKEN",token);
            if (key!=null) request.header("Idempotency-Key",key);
            var response=http.send(request.method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
            if (expected>=0) assertThat(response.statusCode()).as("%s %s %s",method,path,response.body()).isEqualTo(expected);
            var result=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(response.body()); result.put("httpStatus",response.statusCode()); return result;
        }
    }
}
