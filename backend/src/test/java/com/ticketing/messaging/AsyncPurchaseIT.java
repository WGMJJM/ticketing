package com.ticketing.messaging;

import com.ticketing.config.OutboxProperties;
import com.ticketing.AccountTestSupport;
import com.ticketing.entity.OutboxEvent;
import com.ticketing.entity.PurchaseRequest;
import com.ticketing.enums.InventoryChangeReason;
import com.ticketing.mapper.OrderMapper;
import com.ticketing.mapper.OutboxMapper;
import com.ticketing.mapper.PurchaseRequestMapper;
import com.ticketing.service.AccountService;
import com.ticketing.service.OutboxService;
import com.ticketing.service.PurchaseOrderWorker;
import com.ticketing.service.PurchaseProcessing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 新协议以真实HTTP、MySQL、RabbitMQ消费及提交后确认验证；仅明确的故障点使用Spy注入。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "server.servlet.session.cookie.secure=false","ticketing.admin-bootstrap.enabled=false",
    "ticketing.purchase.capacity=200","ticketing.purchase.queue-seconds=120","ticketing.purchase.lease-seconds=2",
    "ticketing.purchase.consumer.concurrency=4","ticketing.purchase.consumer.prefetch=3",
    "ticketing.purchase.recovery-seconds=1","ticketing.purchase.messaging-enabled=true",
    "ticketing.messaging.jobs-enabled=false",
    "ticketing.waitlist.outbox.max-attempts=2",
    "ticketing.purchase.exchange=ticketing.purchase.stage14.it","ticketing.purchase.queue=ticketing.purchase.stage14.it.orders",
    "ticketing.waitlist.messaging.enabled=true","ticketing.waitlist.exchange=ticketing.waitlist.stage14.it",
    "ticketing.waitlist.queue=ticketing.waitlist.stage14.it.dispatch",
    "spring.rabbitmq.listener.simple.concurrency=2","spring.rabbitmq.listener.simple.max-concurrency=2",
    "spring.rabbitmq.listener.simple.prefetch=1",
    "spring.rabbitmq.listener.simple.auto-startup=false","spring.rabbitmq.listener.simple.retry.max-attempts=2",
    "spring.rabbitmq.listener.simple.retry.initial-interval=100ms","spring.rabbitmq.listener.simple.retry.max-interval=100ms"})
class AsyncPurchaseIT {
    private static final String QUEUE="ticketing.purchase.stage14.it.orders";
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired AccountService accounts;
    @Autowired PurchaseProcessing processing;
    @Autowired PurchaseRequestMapper requests;
    @Autowired PurchaseOrderWorker worker;
    @Autowired RabbitListenerEndpointRegistry registry;
    @Autowired AmqpAdmin broker;
    @Autowired RabbitTemplate rabbit;
    @Autowired OutboxService publisherState;
    @Autowired Declarables purchaseTopology;
    @Autowired Declarables waitlistTopology;
    @Autowired InventoryEvents inventoryEvents;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired io.micrometer.core.instrument.MeterRegistry meters;
    @MockitoSpyBean OutboxPublisher publisher;
    @MockitoSpyBean OutboxMapper eventOutbox;
    @MockitoSpyBean com.ticketing.mapper.OrderMapper orders;
    @MockitoSpyBean com.ticketing.messaging.PurchaseOrderConsumer consumer;
    final List<Long> users=new ArrayList<>();
    long activity,session,sku;
    Client buyer,other;

    @BeforeEach void fixture() throws Exception {
        registry.stop();
        List<Declarable> declarations=new ArrayList<>(purchaseTopology.getDeclarables());
        declarations.addAll(waitlistTopology.getDeclarables());
        for(var declaration:declarations) {
            if(declaration instanceof Exchange v) broker.declareExchange(v);
            if(declaration instanceof org.springframework.amqp.core.Queue v) broker.declareQueue(v);
            if(declaration instanceof Binding v) broker.declareBinding(v);
        }
        broker.purgeQueue(QUEUE,false);broker.purgeQueue(QUEUE+".dead",false);
        broker.purgeQueue("ticketing.waitlist.stage14.it.dispatch",false);
        broker.purgeQueue("ticketing.waitlist.stage14.it.dispatch.dead",false);
        buyer=client();other=client();
        String title="异步验收"+UUID.randomUUID();
        db.update("INSERT INTO activity(title,category,city,venue,status,published_at) VALUES(?,'CONCERT','上海','隔离验收场馆','PUBLISHED',UTC_TIMESTAMP(6))",title);
        activity=db.queryForObject("SELECT id FROM activity WHERE title=?",Long.class,title);
        db.update("INSERT INTO activity_session(activity_id,title,starts_at,ends_at,sale_starts_at) VALUES(?,'异步场次',TIMESTAMPADD(DAY,1,UTC_TIMESTAMP(6)),TIMESTAMPADD(DAY,2,UTC_TIMESTAMP(6)),TIMESTAMPADD(DAY,-1,UTC_TIMESTAMP(6)))",activity);
        session=db.queryForObject("SELECT id FROM activity_session WHERE activity_id=?",Long.class,activity);
        sku=sku(200);
    }

    @Test void purchaseConsumerConcurrencyAndPrefetchDoNotChangeWaitlistConsumer() {
        var purchase = registry.getListenerContainer("purchaseOrders");
        var waitlist = registry.getListenerContainer("waitlistDispatch");
        assertThat(purchase).isInstanceOf(org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer.class);
        assertThat(waitlist).isInstanceOf(org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer.class);
        var purchaseContainer = (org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer) purchase;
        var waitlistContainer = (org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer) waitlist;
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(purchaseContainer,"concurrentConsumers")).isEqualTo(4);
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(purchaseContainer,"maxConcurrentConsumers")).isEqualTo(4);
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(purchaseContainer,"prefetchCount")).isEqualTo(3);
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(waitlistContainer,"concurrentConsumers")).isEqualTo(2);
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(waitlistContainer,"maxConcurrentConsumers")).isEqualTo(2);
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(waitlistContainer,"prefetchCount")).isEqualTo(1);
    }
    @AfterEach void cleanup() {
        registry.stop();reset(publisher,eventOutbox,orders,consumer);
        // 测试只清理当前夹具请求，先补偿预算再删除。生产不会删除持久请求。
        for(long user:users) {
            db.update("UPDATE purchase_capacity SET pending_count=pending_count-(SELECT COUNT(*) FROM purchase_request WHERE user_id=? AND status IN('QUEUED','PROCESSING')) WHERE id=1",user);
            db.update("DELETE e FROM outbox_event e JOIN purchase_request p ON e.aggregate_id=p.id WHERE e.aggregate_type='PURCHASE_REQUEST' AND p.user_id=?",user);
            db.update("DELETE FROM purchase_request WHERE user_id=?",user);
        }
        db.update("DELETE e FROM outbox_event e JOIN ticket_sku s ON e.aggregate_id=s.id WHERE e.aggregate_type='TICKET_SKU' AND s.session_id=?",session);
        for(String table:List.of("order_aftercare","refund_record","electronic_ticket","waitlist_entry","payment_record","ticket_order_item")) {
            String column=table.equals("waitlist_entry")?"reserved_order_id":"order_id";
            db.update("DELETE t FROM "+table+" t JOIN ticket_order o ON o.id=t."+column+" WHERE o.session_id=?",session);
        }
        db.update("DELETE FROM ticket_order WHERE session_id=?",session);
        db.update("DELETE FROM user_session_quota WHERE session_id=?",session);
        db.update("DELETE FROM ticket_sku WHERE session_id=?",session);
        db.update("DELETE FROM activity_session WHERE id=?",session);
        db.update("DELETE FROM activity WHERE id=?",activity);
        for(long user:users) db.update("DELETE FROM user_account WHERE id=?",user);
        users.clear();
    }

    @Test void acceptedIsDurableButDoesNotCreateOrderOrReserveUntilRealConsumerRuns() throws Exception {
        JsonNode request=accept(buyer,sku,2,"durable");
        assertThat(request.path("status").asText()).isEqualTo("QUEUED");
        assertThat(request.path("requestId").isTextual()).isTrue();
        assertThat(request.path("orderId").isNull()).isTrue();
        assertThat(count("ticket_order")).isZero();stock(0);capacity(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_id=?",Integer.class,PurchaseCommand.eventId(request.path("requestId").asLong()))).isEqualTo(1);
        startConsumer();JsonNode done=await(buyer,request.path("requestId").asText());
        assertThat(done.path("status").asText()).isEqualTo("SUCCEEDED");stock(2);capacity(0);
        JsonNode order=buyer.call("GET","/orders/"+done.path("orderId").asText(),null,null,200).path("data");
        assertThat(order.path("totalAmountCents").asText()).isEqualTo("24690");
        assertThat(Duration.between(java.time.Instant.parse(order.path("createdAt").asText()),java.time.Instant.parse(order.path("expiresAt").asText())).toMinutes()).isEqualTo(15);
        other.call("GET","/purchase-requests/"+request.path("requestId").asText(),null,null,404);
        other.call("GET","/purchase-requests/9223372036854775807",null,null,404);
        assertThat(buyer.call("GET","/purchase-requests",null,null,200).path("data")).isEmpty();
    }

    @Test void invalidNewApplicationsAreRejectedBeforeCapacityAndOutboxButCommittedReplaySurvivesOffSale() throws Exception {
        var original=accept(buyer,sku,1,"precheck-replay");
        db.update("UPDATE ticket_sku SET status='OFF_SALE' WHERE id=?",sku);
        assertThat(accept(buyer,sku,1,"precheck-replay").path("requestId")).isEqualTo(original.path("requestId"));
        buyer.call("POST","/orders",Map.of("skuId",Long.toString(sku),"quantity",1),"off-sale",409);
        buyer.call("POST","/orders",Map.of("skuId",Long.toString(Long.MAX_VALUE),"quantity",1),"missing",404);
        db.update("UPDATE ticket_sku SET status='ON_SALE' WHERE id=?",sku);
        db.update("UPDATE activity SET status='OFFLINE' WHERE id=?",activity);
        buyer.call("POST","/orders",Map.of("skuId",Long.toString(sku),"quantity",1),"offline",409);
        db.update("UPDATE activity SET status='PUBLISHED' WHERE id=?",activity);
        db.update("UPDATE activity_session SET status='CANCELLED' WHERE id=?",session);
        buyer.call("POST","/orders",Map.of("skuId",Long.toString(sku),"quantity",1),"cancelled",409);
        db.update("UPDATE activity_session SET status='SCHEDULED' WHERE id=?",session);
        db.update("UPDATE ticket_sku SET sale_starts_at=TIMESTAMPADD(HOUR,-2,UTC_TIMESTAMP(6)),sale_ends_at=TIMESTAMPADD(HOUR,-1,UTC_TIMESTAMP(6)) WHERE id=?",sku);
        buyer.call("POST","/orders",Map.of("skuId",Long.toString(sku),"quantity",1),"ended",409);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_request WHERE user_id=?",Integer.class,buyer.id)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_id=?",Integer.class,PurchaseCommand.eventId(original.path("requestId").asLong()))).isEqualTo(1);
        capacity(1);stock(0);
        db.update("UPDATE ticket_sku SET sale_ends_at=TIMESTAMPADD(HOUR,1,UTC_TIMESTAMP(6)) WHERE id=?",sku);
        accept(buyer,sku,1,"ended");capacity(2); // 预检拒绝未持久化失败键，恢复开售后原键可首次受理。
    }

    @Test void readOnlyKeyLookupIsOwnedNoStoreAndRechecksDisabledAccount() throws Exception {
        var accepted=accept(buyer,sku,1,"lost-response");
        var response=buyer.send("GET","/purchase-requests/lookup",null,"lost-response");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(json.readTree(response.body()).path("data").path("requestId")).isEqualTo(accepted.path("requestId"));
        other.call("GET","/purchase-requests/lookup",null,"lost-response",404);
        buyer.call("GET","/purchase-requests/lookup",null,"absent",404);
        new Client().call("GET","/purchase-requests/lookup",null,"lost-response",401);
        db.update("UPDATE user_account SET status='DISABLED' WHERE id=?",buyer.id);
        buyer.call("GET","/purchase-requests/lookup",null,"lost-response",401);
        capacity(1);stock(0);
    }

    @Test void concurrentSameKeyHasOneBudgetOneCommandOneOrderAndNoDeadlineRefresh() throws Exception {
        var responses=parallel(20,i->accept(buyer,sku,1,"same"));
        Set<String> ids=new HashSet<>();responses.forEach(v->ids.add(v.path("requestId").asText()));assertThat(ids).hasSize(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_request WHERE user_id=?",Integer.class,buyer.id)).isEqualTo(1);capacity(1);
        buyer.call("POST","/orders",Map.of("skuId",Long.toString(sku),"quantity",2),"same",409);
        assertThat(accept(buyer,sku,1,"same").path("deadlineAt")).isEqualTo(responses.getFirst().path("deadlineAt"));
        startConsumer();JsonNode result=await(buyer,ids.iterator().next());
        assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");stock(1);capacity(0);
        JsonNode replay=buyer.call("POST","/orders",Map.of("skuId",Long.toString(sku),"quantity",1),"same",200).path("data");
        assertThat(replay.path("requestId").asText()).isEqualTo(ids.iterator().next());
        assertThat(count("ticket_order")).isEqualTo(1);
    }

    @Test void hundredRealConcurrentBuyersForOneTicketHaveOneSuccessfulReservation() throws Exception {
        db.update("UPDATE ticket_sku SET total_stock=1 WHERE id=?",sku);
        List<Client> buyers=new ArrayList<>(List.of(buyer,other));for(int i=2;i<100;i++) buyers.add(client());
        List<JsonNode> accepted=parallel(100,i->accept(buyers.get(i),sku,1,"hot"));
        stock(0);capacity(100);startConsumer();publisher.publishBatch();
        int successes=0,failures=0;
        for(int i=0;i<100;i++) {
            String state=await(buyers.get(i),accepted.get(i).path("requestId").asText()).path("status").asText();
            if(state.equals("SUCCEEDED"))successes++;else if(state.equals("FAILED"))failures++;
        }
        assertThat(successes).isEqualTo(1);assertThat(failures).isEqualTo(99);
        stock(1);capacity(0);assertThat(count("ticket_order")).isEqualTo(1);
    }

    @Test void fullBudgetRejectsWithoutFakeAcceptanceAndReplayDoesNotConsumeAgain() throws Exception {
        // 占用独立夹具的真实200个请求，不伪造容量计数。
        for(int i=0;i<200;i++) accept(buyer,sku,1,"capacity-"+i);
        capacity(200);
        var response=other.send("POST","/orders",Map.of("skuId",Long.toString(sku),"quantity",1),"full");
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Retry-After")).contains("2");
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("PURCHASE_QUEUE_FULL");
        accept(buyer,sku,1,"capacity-0");capacity(200);stock(0);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_request WHERE user_id=?",Integer.class,other.id)).isZero();
    }

    @Test void outboxFailureRollsBackRequestAndBudgetAndTemporaryTradeFailureIsRecoverable() throws Exception {
        doThrow(new DataAccessResourceFailureException("验收受理故障")).when(eventOutbox).insert(any(OutboxEvent.class));
        buyer.call("POST","/orders",Map.of("skuId",Long.toString(sku),"quantity",1),"rollback",500);
        capacity(0);assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_request WHERE user_id=?",Integer.class,buyer.id)).isZero();
        reset(eventOutbox);
        long id=accept(buyer,sku,1,"rollback").path("requestId").asLong();
        doThrow(new DataAccessResourceFailureException("验收明细故障")).when(orders).insertItem(any(),any(),anyInt());
        assertThatThrownBy(()->worker.process(id)).isInstanceOf(DataAccessResourceFailureException.class);
        stock(0);capacity(1);assertThat(requests.find(id).status()).isEqualTo("PROCESSING");
        reset(orders);expireLease(id);processing.recover(id);startConsumer();
        assertThat(await(buyer,Long.toString(id)).path("status").asText()).isEqualTo("SUCCEEDED");stock(1);capacity(0);
    }

    @Test void staleTokenAndExpiryRaceCannotCommitALateOrderOrReleaseCapacityTwice() throws Exception {
        long id=accept(buyer,sku,1,"fence").path("requestId").asLong();
        PurchaseRequest old=processing.claim(id);expireLease(id);PurchaseRequest current=processing.claim(id);
        assertThat(current.processingToken()).isGreaterThan(old.processingToken());
        assertThatThrownBy(()->worker.complete(old)).isInstanceOf(IllegalStateException.class);stock(0);capacity(1);
        db.update("UPDATE purchase_request SET accepted_at=TIMESTAMPADD(SECOND,-10,UTC_TIMESTAMP(6)),deadline_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE id=?",id);
        processing.recover(id);
        assertThatThrownBy(()->worker.complete(current)).isInstanceOf(IllegalStateException.class);
        processing.recover(id);worker.process(id);assertThat(requests.find(id).status()).isEqualTo("EXPIRED");stock(0);capacity(0);
        assertThat(count("ticket_order")).isZero();
    }

    @Test void committedCurrentFactsRejectDisabledAccountStoppedSessionAndCrossSkuQuota() throws Exception {
        long disabled=accept(buyer,sku,1,"disabled").path("requestId").asLong();
        db.update("UPDATE user_account SET status='DISABLED' WHERE id=?",buyer.id);
        startConsumer();publisher.publishBatch();
        waitDatabase(disabled,"FAILED");assertThat(requests.find(disabled).failureCode()).isEqualTo("ACCOUNT_UNAVAILABLE");stock(0);capacity(0);
        registry.stop();long stopped=accept(other,sku,1,"stopped").path("requestId").asLong();
        db.update("UPDATE activity_session SET status='CANCELLED' WHERE id=?",session);startConsumer();
        assertThat(await(other,Long.toString(stopped)).path("status").asText()).isEqualTo("FAILED");stock(0);capacity(0);
        registry.stop();db.update("UPDATE activity_session SET status='SCHEDULED' WHERE id=?",session);
        long sku2=sku(50);
        var first=accept(other,sku,3,"quota-1");var second=accept(other,sku2,3,"quota-2");startConsumer();
        var a=await(other,first.path("requestId").asText());var b=await(other,second.path("requestId").asText());
        assertThat(List.of(a.path("status").asText(),b.path("status").asText())).containsExactlyInAnyOrder("SUCCEEDED","FAILED");
        assertThat(db.queryForObject("SELECT SUM(locked_stock) FROM ticket_sku WHERE session_id=?",Integer.class,session)).isEqualTo(3);capacity(0);
    }

    @Test void duplicateDeliveryConfirmLossAndCommittedButUnackedOrderRemainIdempotent() throws Exception {
        long id=accept(buyer,sku,1,"duplicates").path("requestId").asLong();
        doThrow(new TimeoutException("验收确认丢失")).when(publisher).awaitConfirm(any());
        // awaitConfirm为protected：真实发送后在发布器确认边界注入故障。
        startConsumer();publisher.publishBatch();waitDatabase(id,"SUCCEEDED");
        reset(publisher);db.update("UPDATE outbox_event SET next_retry_at=UTC_TIMESTAMP(6) WHERE event_id=?",PurchaseCommand.eventId(id));publisher.publishBatch();
        for(int i=0;i<5;i++) {
            MessageProperties properties=new MessageProperties();properties.setMessageId(PurchaseCommand.eventId(id));
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            rabbit.send("ticketing.purchase.stage14.it",PurchaseCommand.ROUTING_KEY,new Message(json.writeValueAsBytes(new PurchaseCommand(PurchaseCommand.eventId(id),Long.toString(id),1)),properties));
        }
        worker.process(id);assertThat(count("ticket_order")).isEqualTo(1);stock(1);capacity(0);
    }

    @Test void recoveryIsBoundedAndReusesOneOutboxRecord() throws Exception {
        long id=accept(buyer,sku,1,"recover").path("requestId").asLong();
        PurchaseRequest accepted=requests.find(id);
        assertThat(Duration.between(accepted.acceptedAt(),accepted.nextRecoveryAt())).isEqualTo(Duration.ofSeconds(1));
        publisher.publishBatch(); // 队列有消息但消费者停止，SENT不等于订单完成。
        assertThat(requests.find(id).status()).isEqualTo("QUEUED");
        db.update("UPDATE purchase_request SET next_recovery_at=TIMESTAMPADD(SECOND,60,UTC_TIMESTAMP(6)) WHERE id=?",id);
        processing.recover(id);
        assertThat(db.queryForObject("SELECT status FROM outbox_event WHERE event_id=?",String.class,PurchaseCommand.eventId(id))).isEqualTo("SENT");
        db.update("UPDATE purchase_request SET next_recovery_at=UTC_TIMESTAMP(6) WHERE id=?",id);
        for(int i=0;i<20;i++)processing.recover(id);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_id=?",Integer.class,PurchaseCommand.eventId(id))).isEqualTo(1);
        startConsumer();assertThat(await(buyer,Long.toString(id)).path("status").asText()).isEqualTo("SUCCEEDED");stock(1);capacity(0);
    }

    @Test void failedPurchaseCommandTerminatesActiveRequestAndRejectsLateMessage() throws Exception {
        long id=accept(buyer,sku,1,"publish-exhausted-active").path("requestId").asLong();
        String unavailableExchange="ticketing.purchase.missing."+UUID.randomUUID();
        OutboxPublisher failing=new OutboxPublisher(publisherState,rabbit,"ticketing.waitlist.stage14.it",unavailableExchange,120,
                new OutboxProperties(1,5000,30,2,1,2,1),meters);
        for(int attempt=0;attempt<2;attempt++) {
            db.update("UPDATE outbox_event SET next_retry_at=UTC_TIMESTAMP(6) WHERE event_id=?",PurchaseCommand.eventId(id));
            failing.publishBatch();
        }
        assertThat(eventOutbox.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OutboxEvent>()
                .eq(OutboxEvent::getEventId,PurchaseCommand.eventId(id))).getStatus()).isEqualTo("FAILED");
        assertThat(requests.find(id).status()).isEqualTo("QUEUED");
        db.update("UPDATE purchase_request SET next_recovery_at=UTC_TIMESTAMP(6) WHERE id=?",id);
        processing.recover(id);
        PurchaseRequest failed=requests.find(id);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.failureCode()).isEqualTo("PURCHASE_COMMAND_DELIVERY_EXHAUSTED");
        assertThat(buyer.call("GET","/purchase-requests/"+id,null,null,200).path("data").path("failureMessage").asText())
                .contains("本次未创建订单");
        capacity(0);stock(0);assertThat(count("ticket_order")).isZero();

        MessageProperties lateProperties=new MessageProperties();
        lateProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        lateProperties.setMessageId(PurchaseCommand.eventId(id));
        rabbit.send("ticketing.purchase.stage14.it",PurchaseCommand.ROUTING_KEY,
                new Message(json.writeValueAsBytes(new PurchaseCommand(PurchaseCommand.eventId(id),Long.toString(id),1)),lateProperties));
        startConsumer();
        long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();
        while(System.nanoTime()<deadline && ((Number)broker.getQueueProperties(QUEUE).get("QUEUE_MESSAGE_COUNT")).intValue()>0) Thread.sleep(50);
        assertThat(((Number)broker.getQueueProperties(QUEUE).get("QUEUE_MESSAGE_COUNT")).intValue()).isZero();
        processing.recover(id);worker.process(id);
        assertThat(requests.find(id).status()).isEqualTo("FAILED");
        assertThat(count("ticket_order")).isZero();capacity(0);stock(0);
    }

    @Test void commitBeforeAcknowledgementFailureAndMalformedMessagesUseRealBrokerRedeliveryAndDeadLetter() throws Exception {
        long id=accept(buyer,sku,1,"ack-loss").path("requestId").asLong();
        java.util.concurrent.atomic.AtomicInteger attempts=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call->{
            Object result=call.callRealMethod();
            if(attempts.getAndIncrement()==0) throw new org.springframework.amqp.AmqpException("验收提交后确认前中断");
            return result;
        }).when(consumer).consume(any(Message.class));
        startConsumer();assertThat(await(buyer,Long.toString(id)).path("status").asText()).isEqualTo("SUCCEEDED");
        long end=System.nanoTime()+Duration.ofSeconds(10).toNanos();
        while(attempts.get()<2&&System.nanoTime()<end)Thread.sleep(50);
        assertThat(attempts.get()).isGreaterThanOrEqualTo(2);stock(1);capacity(0);assertThat(count("ticket_order")).isEqualTo(1);
        reset(consumer);
        MessageProperties properties=new MessageProperties();properties.setMessageId("forged");
        rabbit.send("ticketing.purchase.stage14.it",PurchaseCommand.ROUTING_KEY,new Message("{\"eventId\":\"forged\",\"requestId\":\"1\",\"version\":1}".getBytes(StandardCharsets.UTF_8),properties));
        end=System.nanoTime()+Duration.ofSeconds(10).toNanos();
        while(System.nanoTime()<end && ((Number)broker.getQueueProperties(QUEUE+".dead").get("QUEUE_MESSAGE_COUNT")).intValue()<1)Thread.sleep(50);
        assertThat(((Number)broker.getQueueProperties(QUEUE+".dead").get("QUEUE_MESSAGE_COUNT")).intValue()).isEqualTo(1);
        stock(1);capacity(0);
    }

    @Test void expiryAndOldPublisherConfirmRaceClosePendingOutboxAndReleaseBudgetOnce() throws Exception {
        long id=accept(buyer,sku,1,"expire-outbox").path("requestId").asLong();
        OutboxEvent old=publisherState.claimNext();
        assertThat(old.getEventType()).isEqualTo(PurchaseCommand.TYPE);
        assertThat(old.getAggregateId()).isEqualTo(id);
        db.update("UPDATE purchase_request SET accepted_at=TIMESTAMPADD(SECOND,-10,UTC_TIMESTAMP(6)),deadline_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE id=?",id);
        processing.recover(id);processing.recover(id);
        assertThat(requests.find(id).status()).isEqualTo("EXPIRED");
        assertThat(publisherState.sent(old)).isFalse();
        assertThat(eventOutbox.selectById(old.getId()).getStatus()).isEqualTo("FAILED");
        stock(0);capacity(0);worker.process(id);assertThat(count("ticket_order")).isZero();
    }

    @Test void purchaseBacklogDoesNotStarveInventoryChannel() throws Exception {
        for(int i=0;i<100;i++)accept(buyer,sku,1,"fair-"+i);
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(
                status->inventoryEvents.append(sku,InventoryChangeReason.STOCK_INCREASED));
        publisher.publishBatch();
        assertThat(db.queryForObject("SELECT status FROM outbox_event WHERE aggregate_type='TICKET_SKU' AND aggregate_id=?",String.class,sku)).isEqualTo("SENT");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='PURCHASE_REQUEST_ACCEPTED' AND status='SENT'",Integer.class)).isEqualTo(99);
        stock(0);capacity(100);
    }

    @Test void expiredBrokerCopyIsDeadLetteredAndDatabaseRecoveryReusesTheSameRequest() throws Exception {
        long id=accept(buyer,sku,1,"broker-ttl").path("requestId").asLong();
        new OutboxPublisher(publisherState,rabbit,"ticketing.waitlist.stage14.it","ticketing.purchase.stage14.it",5,
                new OutboxProperties(100,5000,30,10,2,300, 1), new io.micrometer.core.instrument.simple.SimpleMeterRegistry()).publishBatch();
        long end=System.nanoTime()+Duration.ofSeconds(12).toNanos();
        while(System.nanoTime()<end && ((Number)broker.getQueueProperties(QUEUE+".dead").get("QUEUE_MESSAGE_COUNT")).intValue()<1)Thread.sleep(100);
        assertThat(((Number)broker.getQueueProperties(QUEUE+".dead").get("QUEUE_MESSAGE_COUNT")).intValue()).isEqualTo(1);
        assertThat(((Number)broker.getQueueProperties(QUEUE).get("QUEUE_MESSAGE_COUNT")).intValue()).isZero();
        assertThat(requests.find(id).status()).isEqualTo("QUEUED");stock(0);capacity(1);
        processing.recover(id);startConsumer();
        assertThat(await(buyer,Long.toString(id)).path("status").asText()).isEqualTo("SUCCEEDED");
        stock(1);capacity(0);assertThat(count("ticket_order")).isEqualTo(1);
    }

    long sku(int stock) {
        String name="异步票"+UUID.randomUUID();
        db.update("INSERT INTO ticket_sku(session_id,name,price_cents,total_stock,sale_starts_at,sale_ends_at,status) VALUES(?,?,12345,?,TIMESTAMPADD(HOUR,-1,UTC_TIMESTAMP(6)),TIMESTAMPADD(HOUR,1,UTC_TIMESTAMP(6)),'ON_SALE')",session,name,stock);
        return db.queryForObject("SELECT id FROM ticket_sku WHERE session_id=? AND name=?",Long.class,session,name);
    }
    void startConsumer() { registry.getListenerContainer("purchaseOrders").start(); }
    JsonNode accept(Client client,long sku,int quantity,String key)throws Exception {return client.call("POST","/orders",Map.of("skuId",Long.toString(sku),"quantity",quantity),key,202).path("data");}
    JsonNode await(Client client,String id)throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(25).toNanos();
        while(System.nanoTime()<end) {
            publisher.publishBatch();JsonNode value=client.call("GET","/purchase-requests/"+id,null,null,200).path("data");
            if(List.of("SUCCEEDED","FAILED","EXPIRED").contains(value.path("status").asText()))return value;
            Thread.sleep(50);
        }
        throw new AssertionError("购票请求未在限定时间内终结");
    }
    void waitDatabase(long id,String expected)throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(20).toNanos();
        while(System.nanoTime()<end&&!requests.find(id).status().equals(expected))Thread.sleep(50);
        assertThat(requests.find(id).status()).isEqualTo(expected);
    }
    void expireLease(long id){db.update("UPDATE purchase_request SET processing_lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)),next_recovery_at=UTC_TIMESTAMP(6) WHERE id=?",id);}
    int count(String table){return db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE session_id=?",Integer.class,session);}
    void stock(int locked){assertThat(db.queryForObject("SELECT locked_stock FROM ticket_sku WHERE id=?",Integer.class,sku)).isEqualTo(locked);com.ticketing.PurchaseTestSupport.assertQuotaConsistent(db,session);}
    void capacity(int value){assertThat(db.queryForObject("SELECT pending_count FROM purchase_capacity WHERE id=1",Integer.class)).isEqualTo(value);}
    Client client()throws Exception {
        String name="async_"+UUID.randomUUID().toString().replace("-","").substring(0,22),password=UUID.randomUUID().toString();
        long id=Long.parseLong(AccountTestSupport.legacyUser(db,name,password).id());users.add(id);
        Client client=new Client();client.id=id;client.csrf();
        client.form("/auth/login","username="+name+"&password="+URLEncoder.encode(password,StandardCharsets.UTF_8));client.csrf();return client;
    }
    <T> List<T> parallel(int n,Checked<T> action)throws Exception {
        CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(n)) {
            List<Future<T>> futures=new ArrayList<>();for(int i=0;i<n;i++){int index=i;futures.add(pool.submit(()->{start.await();return action.run(index);}));}
            start.countDown();List<T> values=new ArrayList<>();for(var future:futures)values.add(future.get(40,TimeUnit.SECONDS));return values;
        }
    }
    interface Checked<T>{T run(int i)throws Exception;}
    class Client {
        long id;String token="";
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        void csrf()throws Exception{token=call("GET","/auth/csrf",null,null,200).path("data").path("token").asText();}
        void form(String path,String body)throws Exception{assertThat(sendRaw("POST",path,body,"application/x-www-form-urlencoded",null).statusCode()).isEqualTo(200);}
        JsonNode call(String method,String path,Object body,String key,int expected)throws Exception {
            var response=send(method,path,body,key);assertThat(response.statusCode()).as("%s %s",method,path).isEqualTo(expected);return json.readTree(response.body());
        }
        HttpResponse<String> send(String method,String path,Object body,String key)throws Exception{return sendRaw(method,path,body==null?"":json.writeValueAsString(body),"application/json",key);}
        HttpResponse<String> sendRaw(String method,String path,String body,String type,String key)throws Exception {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).timeout(Duration.ofSeconds(30)).header("Content-Type",type);
            if(!token.isEmpty())request.header("X-CSRF-TOKEN",token);if(key!=null)request.header("Idempotency-Key",key);
            return http.send(request.method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        }
    }
}
