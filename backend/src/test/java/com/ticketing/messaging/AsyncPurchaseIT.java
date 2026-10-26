package com.ticketing.messaging;

import com.ticketing.AccountTestSupport;
import com.ticketing.config.PurchaseProperties;
import com.ticketing.dto.JoinWaitlistRequest;
import com.ticketing.entity.PurchaseRequest;
import com.ticketing.exception.BusinessException;
import com.ticketing.mapper.OrderMapper;
import com.ticketing.mapper.PurchaseRequestMapper;
import com.ticketing.service.*;
import com.ticketing.task.PurchaseRecoveryJob;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 八个针对性场景：真实 Redis Lua、Rabbit Confirm/消费与 MySQL 本地事务。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "server.servlet.session.cookie.secure=false", "ticketing.admin-bootstrap.enabled=false",
    "ticketing.purchase.queue-seconds=5", "ticketing.purchase.confirm-timeout-ms=500",
    "ticketing.purchase.recovery-seconds=1", "ticketing.purchase.messaging-enabled=true",
    "ticketing.purchase.exchange=ticketing.purchase.direct.it", "ticketing.purchase.queue=ticketing.purchase.direct.it.orders",
    "ticketing.purchase.consumer.concurrency=2", "spring.rabbitmq.listener.simple.auto-startup=false",
    "spring.rabbitmq.listener.simple.retry.max-attempts=2", "spring.rabbitmq.listener.simple.retry.initial-interval=100ms"})
class AsyncPurchaseIT {
    static final String QUEUE="ticketing.purchase.direct.it.orders";
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired PurchaseAdmission admission;
    @Autowired PurchaseOrderWorker worker;
    @Autowired StockSnapshotService inventory;
    @Autowired PurchaseProperties properties;
    @Autowired OrderService orderService;
    @Autowired WaitlistService waitlists;
    @Autowired WaitlistDispatcher dispatcher;
    @Autowired RabbitListenerEndpointRegistry registry;
    @Autowired AmqpAdmin broker;
    @Autowired Declarables purchaseTopology;
    @Autowired StringRedisTemplate redis;
    @Autowired SqlSessionTemplate mybatis;
    @MockitoSpyBean PurchaseRequestMapper requests;
    @MockitoSpyBean OrderMapper orders;
    @MockitoSpyBean RabbitTemplate rabbit;
    @MockitoSpyBean RedisStockReservationService stocks;
    @Autowired PurchasePublisher publisher;
    long activity,session,sku;
    Client buyer,other;

    @BeforeEach void fixture() throws Exception {
        assertThat(System.getenv("MYSQL_PORT")).isEqualTo("13307");
        assertThat(db.queryForObject("SELECT token FROM performance_control.environment_guard",String.class))
                .isEqualTo(System.getenv("RECOVERY_TEST_GUARD"));
        registry.stop();
        for(var declaration:purchaseTopology.getDeclarables()) {
            if(declaration instanceof Exchange v)broker.declareExchange(v);
            if(declaration instanceof org.springframework.amqp.core.Queue v)broker.declareQueue(v);
            if(declaration instanceof Binding v)broker.declareBinding(v);
        }
        broker.purgeQueue(QUEUE,false);broker.purgeQueue(QUEUE+".dead",false);
        buyer=client();other=client();
        String tag="direct_"+UUID.randomUUID();
        db.update("INSERT INTO activity(title,category,city,venue,status,published_at) VALUES(?,'CONCERT','上海','隔离验收','PUBLISHED',UTC_TIMESTAMP(6))",tag);
        activity=db.queryForObject("SELECT id FROM activity WHERE title=?",Long.class,tag);
        db.update("INSERT INTO activity_session(activity_id,title,starts_at,ends_at,sale_starts_at) VALUES(?,'直接购票',TIMESTAMPADD(DAY,1,UTC_TIMESTAMP(6)),TIMESTAMPADD(DAY,2,UTC_TIMESTAMP(6)),TIMESTAMPADD(HOUR,-2,UTC_TIMESTAMP(6)))",activity);
        session=db.queryForObject("SELECT MAX(id) FROM activity_session WHERE activity_id=?",Long.class,activity);
        db.update("INSERT INTO ticket_sku(session_id,name,price_cents,total_stock,max_quantity_per_order,sale_starts_at,sale_ends_at,status) VALUES(?,'普通票',12345,8,4,TIMESTAMPADD(HOUR,-1,UTC_TIMESTAMP(6)),TIMESTAMPADD(HOUR,1,UTC_TIMESTAMP(6)),'ON_SALE')",session);
        sku=db.queryForObject("SELECT MAX(id) FROM ticket_sku WHERE session_id=?",Long.class,session);
        assertThat(inventory.synchronize(sku)).isTrue();
        clearInvocations(requests,orders,rabbit,stocks);
    }
    @AfterEach void stop() {registry.stop();reset(requests,orders,rabbit,stocks);}

    @Test void realHttpAcceptsOnlyAfterConfirmAndQueriesRedisBeforeConsumerCommits() throws Exception {
        var value=accept(buyer,2,"normal");long id=value.path("requestId").asLong();
        assertThat(value.path("status").asText()).isEqualTo("QUEUED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_request WHERE id=?",Integer.class,id)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_type='PURCHASE_REQUEST'",Integer.class)).isZero();
        verify(requests,never()).coordinate(any());verify(requests,never()).byKey(anyLong(),anyString());verify(requests,never()).find(anyLong());
        assertThat(buyer.call("GET","/purchase-requests/lookup",null,"normal",200).path("data").path("requestId")).isEqualTo(value.path("requestId"));
        other.call("GET","/purchase-requests/"+id,null,null,404);
        assertThat(buyer.call("GET","/purchase-requests",null,null,200).path("data")).hasSize(1);
        start();var done=await(buyer,id);assertThat(done.path("status").asText()).isEqualTo("SUCCEEDED");
        long orderId=done.path("orderId").asLong();
        assertThat(db.queryForObject("SELECT total_amount_cents FROM ticket_order WHERE id=?",Long.class,orderId)).isEqualTo(24690);
        assertThat(orderService.pay(buyer.id,orderId)).isNotNull();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM electronic_ticket WHERE order_id=?",Integer.class,orderId)).isEqualTo(2);
        stock(0,2);assertThat(stocks.find(id).status()).isEqualTo("SUCCEEDED");
    }

    @Test void concurrentLastTicketHasOnlyOneReservationAndOneOrder() throws Exception {
        db.update("UPDATE ticket_sku SET total_stock=1 WHERE id=?",sku);inventory.synchronize(sku);
        try(var pool=Executors.newFixedThreadPool(12)) {
            var gate=new CountDownLatch(1);List<Future<PurchaseRequest>> futures=new ArrayList<>();
            for(int i=0;i<12;i++){String key="last-"+i;futures.add(pool.submit(()->{gate.await();try{return stocks.reserve(buyer.id,sku,1,key);}catch(BusinessException e){assertThat(e.getCode()).isEqualTo("TICKET_UNAVAILABLE");return null;}}));}
            gate.countDown();List<PurchaseRequest> winners=new ArrayList<>();
            for(var future:futures){var r=future.get(10,TimeUnit.SECONDS);if(r!=null)winners.add(r);}
            assertThat(winners).hasSize(1);worker.process(winners.getFirst().id());stock(1,0);assertThat(available()).isZero();
        }
    }

    @Test void sameKeyConcurrentReplayKeepsRequestAndDifferentParametersConflict() throws Exception {
        try(var pool=Executors.newFixedThreadPool(8)) {
            List<Future<PurchaseRequest>> futures=new ArrayList<>();
            for(int i=0;i<8;i++)futures.add(pool.submit(()->stocks.reserve(buyer.id,sku,1,"idem")));
            Set<Long> ids=new HashSet<>();for(var f:futures)ids.add(f.get(10,TimeUnit.SECONDS).id());
            assertThat(ids).hasSize(1);assertThat(available()).isEqualTo(7);
            assertThatThrownBy(()->stocks.reserve(buyer.id,sku,2,"idem")).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT"));
            long id=ids.iterator().next();worker.process(id);worker.process(id);stock(1,0);
            assertThat(stocks.reserve(buyer.id,sku,1,"idem").id()).isEqualTo(id);assertThat(available()).isEqualTo(7);
            redis.delete(stocks.requestKey(id)); // 终态详情缓存到期后仍保留精简幂等事实。
            assertThat(stocks.reserve(buyer.id,sku,1,"idem").id()).isEqualTo(id);
            worker.process(id,sku);assertThat(available()).isEqualTo(7);assertThat(orderCount()).isEqualTo(1);
        }
    }

    @Test void duplicateRabbitDeliveriesCommitExactlyOneOrderAndOneQuotaOccupation() throws Exception {
        long id=accept(buyer,1,"duplicate").path("requestId").asLong();
        assertThat(publisher.confirm(id,sku)).isTrue();assertThat(publisher.confirm(id,sku)).isTrue();
        start();assertThat(await(buyer,id).path("status").asText()).isEqualTo("SUCCEEDED");
        worker.process(id);assertThat(orderCount()).isEqualTo(1);stock(1,0);assertThat(available()).isEqualTo(7);
    }

    @Test void failedAndUnknownPublishingPreserveReservationAndUnifiedRecoveryRedelivers() throws Exception {
        doThrow(new org.springframework.amqp.AmqpConnectException(new java.net.ConnectException("isolated fault")))
                .when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        buyer.call("POST","/orders",Map.of("skuId",Long.toString(sku),"quantity",1),"unknown",503);
        PurchaseRequest held=stocks.lookup(buyer.id,"unknown");assertThat(available()).isEqualTo(7);
        reset(rabbit);List<CorrelationData> unknown=new ArrayList<>();
        doAnswer(call->{unknown.add(call.getArgument(3));rabbit.send(call.getArgument(0,String.class),call.getArgument(1,String.class),call.getArgument(2,Message.class));return null;})
                .when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        buyer.call("POST","/orders",Map.of("skuId",Long.toString(sku),"quantity",1),"unknown",503);
        assertThat(stocks.lookup(buyer.id,"unknown").id()).isEqualTo(held.id());assertThat(available()).isEqualTo(7);
        reset(rabbit);unknown.forEach(c->c.getFuture().completeExceptionally(new TimeoutException("unknown outcome")));
        redis.opsForZSet().add(stocks.recoveryKey(),sku+":"+held.id(),0);
        new PurchaseRecoveryJob(stocks,worker,publisher,inventory,properties).scan();
        start();assertThat(await(buyer,held.id()).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(orderCount()).isEqualTo(1);stock(1,0);
    }

    @Test void databaseRollbackAndPostCommitSettlementFailureRecoverWithoutSecondOrder() {
        PurchaseRequest held=stocks.reserve(buyer.id,sku,1,"database-fault");
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("isolated item failure")).when(orders).insertItem(any(),any(),anyInt());
        assertThatThrownBy(()->worker.process(held.id())).isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThat(requests.find(held.id())).isNull();stock(0,0);assertThat(stocks.find(held.id()).status()).isEqualTo("QUEUED");
        reset(orders);doReturn(false).when(stocks).settle(any());
        worker.process(held.id());assertThat(requests.find(held.id()).status()).isEqualTo("SUCCEEDED");
        assertThat(stocks.find(held.id()).status()).isEqualTo("QUEUED");
        reset(stocks);assertThat(worker.recover(held.id(),sku)).isTrue();worker.process(held.id());
        assertThat(orderCount()).isEqualTo(1);stock(1,0);assertThat(available()).isEqualTo(7);
        PurchaseRequest missing=stocks.reserve(buyer.id,sku,1,"lost-proof");
        redis.delete(stocks.requestKey(missing.id()));
        assertThatThrownBy(()->worker.process(missing.id(),sku)).isInstanceOf(BusinessException.class);
        assertThat(inventory.synchronize(sku)).isFalse();
        assertThat(redis.opsForHash().get(stocks.skuKey(sku),"ready")).isEqualTo("0");
        assertThat(available()).isEqualTo(6); // 丢失凭据不能通过重同步凭空返票。
    }

    @Test void expiryArbitrationRacesLateConsumerWithoutSuccessfulOrderAndReturnedStockTogether() throws Exception {
        PurchaseRequest held=stocks.reserve(buyer.id,sku,1,"expiry-race");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{entered.countDown();assertThat(release.await(12,TimeUnit.SECONDS)).isTrue();return mybatis.getMapper(OrderMapper.class).insertItem(call.getArgument(0),call.getArgument(1),call.getArgument(2));})
                .when(orders).insertItem(any(),any(),anyInt());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var late=pool.submit(()->worker.process(held.id()));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();Thread.sleep(5200);
            var expire=pool.submit(()->worker.recover(held.id(),sku));release.countDown();
            late.get(15,TimeUnit.SECONDS);assertThat(expire.get(15,TimeUnit.SECONDS)).isTrue();
        } finally {release.countDown();}
        assertThat(requests.find(held.id()).status()).isEqualTo("EXPIRED");
        assertThat(orderCount()).isZero();stock(0,0);assertThat(available()).isEqualTo(8);
        worker.process(held.id());assertThat(available()).isEqualTo(8);
    }

    @Test void orderReleaseAndFifoWaitlistCannotConsumeUnfinishedOrdinaryReservation() throws Exception {
        db.update("UPDATE ticket_sku SET total_stock=1 WHERE id=?",sku);inventory.synchronize(sku);
        var ordinary=stocks.reserve(buyer.id,sku,1,"before-gate");
        var waiting=waitlists.join(other.id,new JoinWaitlistRequest(Long.toString(sku),1));
        assertThatThrownBy(()->stocks.reserve(buyer.id,sku,1,"after-gate")).isInstanceOfSatisfying(BusinessException.class,
                e->assertThat(e.getCode()).isEqualTo("WAITLIST_PRIORITY"));
        worker.process(ordinary.id());orderService.cancel(buyer.id,requests.find(ordinary.id()).orderId());
        assertThat(dispatcher.dispatch(sku)).isEqualTo(1);start();
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<until && !waitlists.detail(other.id,Long.parseLong(waiting.id())).status().equals("ALLOCATED")) Thread.sleep(30);
        assertThat(waitlists.detail(other.id,Long.parseLong(waiting.id())).status()).isEqualTo("ALLOCATED");
        stock(1,0);assertThat(stocks.find(ordinary.id()).status()).isEqualTo("SUCCEEDED");
    }

    void start(){registry.getListenerContainer("purchaseOrders").start();}
    JsonNode accept(Client who,int quantity,String key)throws Exception{return who.call("POST","/orders",Map.of("skuId",Long.toString(sku),"quantity",quantity),key,202).path("data");}
    JsonNode await(Client who,long id)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<until){var r=who.call("GET","/purchase-requests/"+id,null,null,200).path("data");if(Set.of("SUCCEEDED","FAILED","EXPIRED").contains(r.path("status").asText()))return r;Thread.sleep(30);}
        throw new AssertionError("request did not finish");
    }
    long available(){return Long.parseLong(Objects.requireNonNull(redis.opsForHash().get(stocks.skuKey(sku),"available")).toString());}
    int orderCount(){return db.queryForObject("SELECT COUNT(*) FROM ticket_order WHERE session_id=?",Integer.class,session);}
    void stock(int locked,int sold){assertThat(db.queryForObject("SELECT locked_stock FROM ticket_sku WHERE id=?",Integer.class,sku)).isEqualTo(locked);assertThat(db.queryForObject("SELECT sold_stock FROM ticket_sku WHERE id=?",Integer.class,sku)).isEqualTo(sold);com.ticketing.PurchaseTestSupport.assertQuotaConsistent(db,session);}
    Client client()throws Exception {
        String name="direct_"+UUID.randomUUID().toString().replace("-", "").substring(0,20),password=UUID.randomUUID().toString();
        long id=Long.parseLong(AccountTestSupport.legacyUser(db,name,password).id());
        Client c=new Client();c.id=id;c.csrf();c.form("/auth/login","username="+name+"&password="+URLEncoder.encode(password,StandardCharsets.UTF_8));c.csrf();return c;
    }
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
