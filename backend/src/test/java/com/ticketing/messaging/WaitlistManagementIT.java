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

/** 候补统一链路场景：真实 Redis Lua、Rabbit 消费与 MySQL 本地事务。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "server.servlet.session.cookie.secure=false", "ticketing.admin-bootstrap.enabled=false",
    "ticketing.purchase.queue-seconds=60",
    "ticketing.purchase.recovery-seconds=1", "ticketing.purchase.messaging-enabled=true",
    "ticketing.purchase.jobs-enabled=false", "ticketing.waitlist.jobs.enabled=false",
    "ticketing.purchase.exchange=ticketing.purchase.waitlist.it", "ticketing.purchase.queue=ticketing.purchase.waitlist.it.orders",
    "ticketing.purchase.consumer.concurrency=2", "spring.rabbitmq.listener.simple.auto-startup=false",
    "spring.rabbitmq.listener.simple.retry.max-attempts=2", "spring.rabbitmq.listener.simple.retry.initial-interval=100ms"})
class WaitlistManagementIT {
    static final String QUEUE="ticketing.purchase.waitlist.it.orders";
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
    @Autowired PurchaseRequestMapper requests;
    @MockitoSpyBean OrderMapper orders;
    @MockitoSpyBean RabbitTemplate rabbit;
    @MockitoSpyBean RedisStockReservationService stocks;
    @MockitoSpyBean PurchasePublisher publisher;
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
        clearInvocations(orders,rabbit,stocks,publisher);
    }
    @AfterEach void stop() {registry.stop();reset(orders,rabbit,stocks,publisher);}

    @Test void priorityBlocksNewOrdinaryRequestsButPreservesEarlierReservationAndReplay() throws Exception {
        total(2);
        long id=accept(buyer,2,"before").path("requestId").asLong();
        var entry=join(other,1);
        assertThat(priority()).isEqualTo("1");
        assertThat(buyer.call("POST","/orders",Map.of("skuId",Long.toString(sku),"quantity",1),"new",409)
                .path("code").asText()).isEqualTo("WAITLIST_PRIORITY");
        assertThat(accept(buyer,2,"before").path("requestId").asLong()).isEqualTo(id);
        start();assertThat(await(buyer,id).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(status(entry)).isEqualTo("WAITING");stock(2,0);
    }

    @Test void fifoHeadNeedingTwoKeepsSingleReleasedTicketAndBlocksFollowers() throws Exception {
        total(0);var a=join(buyer,2);var b=join(other,1);Client third=client();var c=join(third,1);
        assertThat(Long.parseLong(a.id())).isLessThan(Long.parseLong(b.id()));
        assertThat(Long.parseLong(b.id())).isLessThan(Long.parseLong(c.id()));
        total(1);assertThat(dispatcher.dispatch(sku)).isZero();
        assertThat(status(a)).isEqualTo("WAITING");assertThat(status(b)).isEqualTo("WAITING");
        assertThat(available()).isEqualTo(1);assertThat(priority()).isEqualTo("1");
        assertThatThrownBy(()->stocks.reserve(third.id,sku,1,"steal")).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo("WAITLIST_PRIORITY"));
        total(2);assertThat(dispatcher.dispatch(sku)).isEqualTo(1);start();awaitAllocated(a);
        assertThat(status(b)).isEqualTo("WAITING");assertThat(status(c)).isEqualTo("WAITING");stock(2,0);
    }

    @Test void headUsesSameLuaQueueConsumerAndCommitsOrderAndAllocationTogether() throws Exception {
        total(0);var entry=join(buyer,1);total(1);assertThat(dispatcher.dispatch(sku)).isEqualTo(1);
        long id=request(entry);assertThat(stocks.find(id).idempotencyOperation()).isEqualTo("WAITLIST_CONFIRM");
        assertThat(stocks.find(id).waitlistEntryId()).isEqualTo(Long.parseLong(entry.id()));
        assertThat(requests.find(id)).isNull();assertThat(status(entry)).isEqualTo("PROCESSING");
        start();long order=awaitAllocated(entry);assertThat(requests.find(id).orderId()).isEqualTo(order);
        assertThat(db.queryForObject("SELECT idempotency_operation FROM ticket_order WHERE id=?",String.class,order)).isEqualTo("WAITLIST_CONFIRM");
        assertThat(db.queryForObject("SELECT status FROM ticket_order WHERE id=?",String.class,order)).isEqualTo("PENDING");
        orderService.pay(buyer.id,order);assertThat(status(entry)).isEqualTo("COMPLETED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM electronic_ticket WHERE order_id=?",Integer.class,order)).isEqualTo(1);
        dispatcher.dispatch(sku);assertThat(priority()).isEqualTo("0");stock(0,1);
    }

    @Test void repeatedInventoryAndWaitlistScansNeverDuplicateReservationOrderOrReturnedStock() throws Exception {
        total(1);var held=stocks.reserve(buyer.id,sku,1,"held");worker.process(held.id());
        var entry=join(other,1);orderService.cancel(buyer.id,requests.find(held.id()).orderId());
        dispatcher.dispatch(sku);long id=request(entry);dispatcher.dispatch(sku);dispatcher.dispatch(sku);
        assertThat(request(entry)).isEqualTo(id);assertThat(available()).isZero();
        start();long order=awaitAllocated(entry);worker.process(id);assertThat(orderCount()).isEqualTo(2);stock(1,0);
        db.update("UPDATE ticket_order SET expires_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)),created_at=TIMESTAMPADD(MINUTE,-20,UTC_TIMESTAMP(6)) WHERE id=?",order);
        assertThat(orderService.expire(other.id,order)).isTrue();assertThat(status(entry)).isEqualTo("EXPIRED");
        dispatcher.dispatch(sku);dispatcher.dispatch(sku);
        assertThat(available()).isEqualTo(1);assertThat(priority()).isEqualTo("0");stock(0,0);
    }

    @Test void processingAndLostSendKeepOneHeadAndStableRequestWhileOthersJoin() throws Exception {
        total(0);var entry=join(buyer,1);total(2);
        doNothing().when(publisher).publish(anyLong(),anyLong());dispatcher.dispatch(sku);
        long id=request(entry);assertThat(status(entry)).isEqualTo("PROCESSING");assertThat(available()).isEqualTo(1);
        var follower=join(other,1);dispatcher.dispatch(sku);assertThat(request(entry)).isEqualTo(id);
        assertThat(status(follower)).isEqualTo("WAITING");assertThat(priority()).isEqualTo("1");
        buyer.call("POST","/waitlist/"+entry.id()+"/cancel",null,null,409);
        reset(publisher);redis.opsForZSet().add(stocks.recoveryKey(),sku+":"+id,0);
        new PurchaseRecoveryJob(stocks,worker,publisher,inventory,properties).scan();start();awaitAllocated(entry);
        assertThat(requests.find(id).status()).isEqualTo("SUCCEEDED");assertThat(orderCount()).isEqualTo(1);
    }

    @Test void concurrentSnapshotAndScanCannotSkipProcessingHead() throws Exception {
        total(0);var head=join(buyer,1);var follower=join(other,1);total(2);
        assertThat(dispatcher.dispatch(sku)).isEqualTo(1);
        long first=request(head);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var snapshot=pool.submit(()->inventory.synchronize(sku));
            var scan=pool.submit(()->dispatcher.dispatch(sku));
            assertThat(snapshot.get(10,TimeUnit.SECONDS)).isFalse();
            assertThat(scan.get(10,TimeUnit.SECONDS)).isZero();
        }
        assertThat(status(head)).isEqualTo("PROCESSING");assertThat(status(follower)).isEqualTo("WAITING");
        assertThat(request(head)).isEqualTo(first);assertThat(available()).isEqualTo(1);
        worker.process(first);dispatcher.dispatch(sku);
        assertThat(status(head)).isEqualTo("ALLOCATED");
        assertThat(status(follower)).isEqualTo("PROCESSING");
        worker.process(request(follower));
        assertThat(status(follower)).isEqualTo("ALLOCATED");assertThat(orderCount()).isEqualTo(2);
    }

    @Test void consumerRollbackAndDefinitiveFailureReuseRecoveryAndSettleBeforeAdvancing() throws Exception {
        total(0);var entry=join(buyer,1);var follower=join(other,1);total(2);dispatcher.dispatch(sku);long id=request(entry);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("isolated rollback")).when(orders).insertItem(any(),any(),anyInt());
        assertThatThrownBy(()->worker.process(id)).isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThat(requests.find(id)).isNull();assertThat(status(entry)).isEqualTo("PROCESSING");stock(0,0);
        reset(orders);db.update("UPDATE user_account SET status='DISABLED' WHERE id=?",buyer.id);
        doReturn(false).when(stocks).settle(any());worker.process(id);
        assertThat(requests.find(id).status()).isEqualTo("FAILED");assertThat(status(entry)).isEqualTo("EXPIRED");
        assertThatThrownBy(()->dispatcher.dispatch(sku)).isInstanceOf(BusinessException.class);
        assertThat(status(follower)).isEqualTo("WAITING");assertThat(available()).isEqualTo(1);
        reset(stocks);worker.recover(id,sku);assertThat(available()).isEqualTo(2);worker.process(id);assertThat(available()).isEqualTo(2);
        dispatcher.dispatch(sku);start();awaitAllocated(follower);assertThat(orderCount()).isEqualTo(1);
        registry.stop();Client retrying=client();var retried=join(retrying,2);total(3);dispatcher.dispatch(sku);
        long expired=request(retried);long now=System.currentTimeMillis();
        redis.opsForHash().put(stocks.requestKey(expired),"acceptedAt",Long.toString(now-2000));
        redis.opsForHash().put(stocks.requestKey(expired),"deadlineAt",Long.toString(now-1000));
        assertThat(worker.recover(expired,sku)).isTrue();assertThat(requests.find(expired).status()).isEqualTo("EXPIRED");
        dispatcher.dispatch(sku);assertThat(status(retried)).isEqualTo("WAITING");dispatcher.dispatch(sku);
        long fresh=request(retried);assertThat(fresh).isNotEqualTo(expired);
        assertThat(stocks.find(fresh).idempotencyKey()).isNotEqualTo(requests.find(expired).idempotencyKey());
        worker.process(fresh);assertThat(status(retried)).isEqualTo("ALLOCATED");
    }

    @Test void cancellationExpiryAndConcurrentEnqueueCannotLeavePriorityOpen() throws Exception {
        total(0);var entry=join(buyer,1);waitlists.cancel(buyer.id,Long.parseLong(entry.id()));
        var closing=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{closing.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return call.callRealMethod();}).when(stocks).closePriority(sku);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var close=pool.submit(()->dispatcher.dispatch(sku));assertThat(closing.await(5,TimeUnit.SECONDS)).isTrue();
            var joining=pool.submit(()->join(other,1));Thread.sleep(80);assertThat(joining.isDone()).isFalse();release.countDown();close.get(10,TimeUnit.SECONDS);
            var next=joining.get(10,TimeUnit.SECONDS);assertThat(priority()).isEqualTo("1");reset(stocks);
            db.update("UPDATE waitlist_entry SET created_at=TIMESTAMPADD(MINUTE,-2,UTC_TIMESTAMP(6)),expires_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE id=?",Long.parseLong(next.id()));
            dispatcher.dispatch(sku);assertThat(status(next)).isEqualTo("EXPIRED");assertThat(priority()).isEqualTo("0");
        } finally {release.countDown();}
    }

    void total(int count){db.update("UPDATE ticket_sku SET total_stock=? WHERE id=?",count,sku);assertThat(inventory.synchronize(sku)).isTrue();}
    com.ticketing.vo.WaitlistView join(Client who,int qty){return waitlists.join(who.id,new JoinWaitlistRequest(Long.toString(sku),qty));}
    String status(com.ticketing.vo.WaitlistView entry){return db.queryForObject("SELECT status FROM waitlist_entry WHERE id=?",String.class,Long.parseLong(entry.id()));}
    String priority(){return Objects.toString(redis.opsForHash().get(stocks.skuKey(sku),"waitlistPriority"));}
    long request(com.ticketing.vo.WaitlistView entry){return db.queryForObject("SELECT purchase_request_id FROM waitlist_entry WHERE id=?",Long.class,Long.parseLong(entry.id()));}
    long awaitAllocated(com.ticketing.vo.WaitlistView entry)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<until){if("ALLOCATED".equals(status(entry)))return db.queryForObject("SELECT reserved_order_id FROM waitlist_entry WHERE id=?",Long.class,Long.parseLong(entry.id()));Thread.sleep(30);}
        throw new AssertionError("waitlist did not allocate: "+status(entry));
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
