package com.ticketing;

import com.ticketing.config.AdmissionLimitProperties;
import com.ticketing.dto.RegisterRequest;
import com.ticketing.exception.BusinessException;
import com.ticketing.service.AccountService;
import com.ticketing.service.PurchaseAdmission;
import com.ticketing.service.PurchaseOrderWorker;
import com.ticketing.service.TicketAdmissionLimiter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

/** 真实Redis原子预算与真实HTTP安全链路；消费者沿用第14阶段数据库事务。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.servlet.session.cookie.secure=false", "ticketing.security.rate-limit.enabled=true",
        "ticketing.security.rate-limit.order-user.limit=6", "ticketing.security.rate-limit.order-user.window-seconds=60",
        "ticketing.purchase.admission-limit.enabled=true", "ticketing.purchase.admission-limit.global-rate=1",
        "ticketing.purchase.admission-limit.global-burst=4", "ticketing.purchase.admission-limit.ticket-rate=1",
        "ticketing.purchase.admission-limit.ticket-burst=2"})
class TicketAdmissionIT {
    static final String NAMESPACE = "s15_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    static final String PREFIX = "ticketing:purchase-admission:v1:" + NAMESPACE + ":";
    static final String SECRET = secret();
    private static String secret() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
    @DynamicPropertySource static void settings(DynamicPropertyRegistry registry) {
        registry.add("ticketing.purchase.admission-limit.namespace", () -> NAMESPACE);
        registry.add("ticketing.security.rate-limit.namespace", () -> NAMESPACE);
        registry.add("ticketing.security.rate-limit.key-secret", () -> SECRET);
    }
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate db;
    @Autowired AccountService accounts;
    @Autowired ObjectMapper json;
    @Autowired PurchaseOrderWorker worker;
    @Autowired PurchaseAdmission admission;
    @LocalServerPort int port;
    long activity, session, sku;
    List<Long> users = new ArrayList<>();

    @BeforeEach void fixture() {
        redis.delete(PREFIX + "global");
        String title = "准入验收" + UUID.randomUUID();
        db.update("INSERT INTO activity(title,category,city,venue,status,published_at) VALUES(?,'CONCERT','上海','隔离准入','PUBLISHED',UTC_TIMESTAMP(6))", title);
        activity = db.queryForObject("SELECT id FROM activity WHERE title=?", Long.class, title);
        db.update("INSERT INTO activity_session(activity_id,title,starts_at,ends_at,sale_starts_at) VALUES(?,'准入场次',TIMESTAMPADD(DAY,1,UTC_TIMESTAMP(6)),TIMESTAMPADD(DAY,2,UTC_TIMESTAMP(6)),TIMESTAMPADD(DAY,-1,UTC_TIMESTAMP(6)))", activity);
        session = db.queryForObject("SELECT id FROM activity_session WHERE activity_id=?", Long.class, activity);
        db.update("INSERT INTO ticket_sku(session_id,name,price_cents,total_stock,sale_starts_at,sale_ends_at,status,max_quantity_per_order) VALUES(?,'准入票档',100,200,TIMESTAMPADD(HOUR,-1,UTC_TIMESTAMP(6)),TIMESTAMPADD(HOUR,1,UTC_TIMESTAMP(6)),'ON_SALE',4)", session);
        sku = db.queryForObject("SELECT id FROM ticket_sku WHERE session_id=?", Long.class, session);
    }
    @AfterEach void cleanup() {
        PurchaseTestSupport.cleanup(db, session);
        db.update("DELETE FROM ticket_order_item WHERE session_id=?", session);
        db.update("DELETE FROM ticket_order WHERE session_id=?", session);
        db.update("DELETE FROM ticket_sku WHERE session_id=?", session);
        db.update("DELETE FROM activity_session WHERE id=?", session);
        db.update("DELETE FROM activity WHERE id=?", activity);
        for (long id : users) db.update("DELETE FROM user_account WHERE id=?", id);
        redis.delete(List.of(PREFIX + "global", PREFIX + "sku:" + sku));
    }

    @Test void realConcurrentLuaCannotExceedInstantaneousOrSustainedBudget() throws Exception {
        String namespace = ownNamespace();
        var limiter = limiter(namespace, 1, 50, 1, 50);
        CountDownLatch start = new CountDownLatch(1);
        int allowed = 0;
        try (var pool = Executors.newFixedThreadPool(100)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 100; i++) results.add(pool.submit(() -> { start.await(); try { limiter.require(sku); return true; }
                catch (BusinessException exception) { assertThat(exception.getStatus().value()).isEqualTo(429); return false; } }));
            long at = System.nanoTime();
            start.countDown();
            for (var result : results) if (result.get(5, TimeUnit.SECONDS)) allowed++;
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - at);
            assertThat(allowed).isBetween(50, 50 + (int)(elapsed / 1000));
        }
        Thread.sleep(1050);
        limiter.require(sku);
        assertThat(redis.getExpire(key(namespace, "sku:" + sku))).isPositive();
        assertThat(Long.parseLong(redis.<String,String>opsForHash().get(key(namespace, "sku:" + sku), "tokens"))).isNotNegative();
    }
    @Test void ticketRejectionDoesNotChargeGlobalAndDifferentTicketsRemainIndependent() throws Exception {
        String namespace = ownNamespace();
        var limiter = limiter(namespace, 1, 5, 1, 1);
        limiter.require(1);
        var before = redis.opsForHash().entries(key(namespace, "global"));
        assertThatThrownBy(() -> limiter.require(1)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo("PURCHASE_TICKET_BUSY");
        assertThat(redis.opsForHash().entries(key(namespace, "global"))).isEqualTo(before);
        limiter.require(2);
        redis.opsForHash().put(key(namespace, "global"), "tokens", "0");
        assertThatThrownBy(() -> limiter.require(3)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo("PURCHASE_GLOBAL_BUSY");
        assertThat(redis.hasKey(key(namespace, "sku:3"))).isFalse();
        Thread.sleep(2100);
        assertThat(redis.hasKey(key(namespace, "sku:1"))).isFalse();
        limiter.require(1);
    }
    @Test void exhaustedTicketDoesNotBlockSameKeyReplayOrConflictAndRejectionsAreNotAccepted() throws Exception {
        var buyer = client();
        var first = buyer.order("first", 1, 202);
        buyer.order("second", 1, 202);
        // 本例验证耗尽时的HTTP幂等协议，不能假定三次HTTP一定在1秒内完成。
        // 仅冻结本例随机命名空间的票档桶；真实TIME补充/TTL由上面的并发Lua测试覆盖。
        redis.delete(PREFIX + "global");
        var freeze = new DefaultRedisScript<Long>("""
                local clock=redis.call('TIME')
                local untilAt=tonumber(clock[1])*1000+math.floor(tonumber(clock[2])/1000)+tonumber(ARGV[1])
                redis.call('HSET',KEYS[1],'tokens',0,'time',untilAt)
                redis.call('PEXPIRE',KEYS[1],ARGV[1])
                return 1
                """, Long.class);
        assertThat(redis.execute(freeze, List.of(PREFIX + "sku:" + sku),
                Long.toString(Duration.ofMinutes(1).toMillis()))).isEqualTo(1L);
        int requests = requestCount(buyer.id);
        long capacity = capacity();
        var rejected = buyer.send("POST", "/orders", body(1), "third", true);
        assertThat(rejected.statusCode()).isEqualTo(429);
        assertThat(json.readTree(rejected.body()).path("code").asText()).isEqualTo("PURCHASE_TICKET_BUSY");
        assertThat(rejected.headers().firstValue("Retry-After")).hasValue("1");
        assertThat(rejected.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(requestCount(buyer.id)).isEqualTo(requests);
        assertThat(capacity()).isEqualTo(capacity);
        assertThat(buyer.order("first", 1, 202).path("requestId")).isEqualTo(first.path("requestId"));
        buyer.order("first", 2, 409);
    }
    @Test void completedReplayIsDatabaseFactAndOriginalUserLimitStillApplies() throws Exception {
        var buyer = client();
        long id = buyer.order("replay", 1, 202).path("requestId").asLong();
        worker.process(id);
        for (int i = 0; i < 5; i++) assertThat(buyer.order("replay", 1, 200).path("status").asText()).isEqualTo("SUCCEEDED");
        var limited = buyer.order("replay", 1, 429);
        assertThat(limited.path("code").asText()).isEqualTo("TOO_MANY_REQUESTS");
        assertThat(requestCount(buyer.id)).isEqualTo(1);
    }
    @Test void malformedLuaStateFailsClosedAndExistingSecurityLimitKeepsItsOwnRedisFailurePolicy() throws Exception {
        var buyer = client();
        redis.opsForValue().set(PREFIX + "global", "wrong-type", Duration.ofSeconds(5));
        long before = capacity();
        var response = buyer.order("bad-redis", 1, 503);
        assertThat(response.path("code").asText()).isEqualTo("PURCHASE_ADMISSION_UNAVAILABLE");
        assertThat(requestCount(buyer.id)).isZero();
        assertThat(capacity()).isEqualTo(before);
        redis.delete(PREFIX + "global");
        buyer.order("bad-redis", 1, 202);
    }
    @Test void csrfIdentityAndInputChecksPrecedeNewRedisKeys() throws Exception {
        var buyer = client();
        var withoutCsrf = buyer.send("POST", "/orders", body(1), "no-csrf", false);
        assertThat(withoutCsrf.statusCode()).isEqualTo(403);
        assertThat(buyer.send("POST", "/orders", Map.of("skuId", "9223372036854775808", "quantity", 1), "overflow", true).statusCode()).isEqualTo(400);
        assertThat(redis.hasKey(PREFIX + "global")).isFalse();
        db.update("UPDATE user_account SET status='DISABLED' WHERE id=?", buyer.id);
        assertThat(buyer.send("POST", "/orders", body(1), "disabled", true).statusCode()).isEqualTo(401);
        assertThat(redis.hasKey(PREFIX + "global")).isFalse();
    }
    @Test void consumerRechecksCurrentSalesAfterSuccessfulAdmission() throws Exception {
        var buyer = client();
        long id = buyer.order("stopped", 1, 202).path("requestId").asLong();
        db.update("UPDATE activity SET status='OFFLINE' WHERE id=?", activity);
        worker.process(id);
        assertThat(db.queryForObject("SELECT status FROM purchase_request WHERE id=?", String.class, id)).isEqualTo("FAILED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ticket_order WHERE session_id=?", Integer.class, session)).isZero();
        assertThat(capacity()).isZero();
    }
    @Test void concurrentNewSameKeyAndThenReplayHaveOneDurableRequest() throws Exception {
        var buyer = client();
        CountDownLatch start = new CountDownLatch(1);
        Set<String> ids = new HashSet<>();
        try (var pool = Executors.newFixedThreadPool(4)) {
            List<Future<HttpResponse<String>>> responses = new ArrayList<>();
            for (int i = 0; i < 4; i++) responses.add(pool.submit(() -> { start.await(); return buyer.send("POST", "/orders", body(1), "concurrent", true); }));
            start.countDown();
            for (var future : responses) {
                var response = future.get(5, TimeUnit.SECONDS);
                assertThat(response.statusCode()).isIn(202, 429);
                if (response.statusCode() == 202) ids.add(json.readTree(response.body()).path("data").path("requestId").asText());
            }
        }
        assertThat(ids).hasSize(1);
        assertThat(buyer.order("concurrent", 1, 202).path("requestId").asText()).isEqualTo(ids.iterator().next());
        assertThat(requestCount(buyer.id)).isEqualTo(1);
        assertThat(capacity()).isEqualTo(1);
    }
    @Test void databaseFullBudgetStillRejectsWithoutNewRequestOrOutbox() throws Exception {
        var buyer = client();
        for (int i = 0; i < 200; i++) admission.accept(buyer.id, Long.toString(sku), 1, "fill-" + i);
        long events = db.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_type='PURCHASE_REQUEST'", Long.class);
        assertThat(buyer.order("overflow", 1, 503).path("code").asText()).isEqualTo("PURCHASE_QUEUE_FULL");
        assertThat(requestCount(buyer.id)).isEqualTo(200);
        assertThat(capacity()).isEqualTo(200);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_type='PURCHASE_REQUEST'", Long.class)).isEqualTo(events);
    }

    TicketAdmissionLimiter limiter(String namespace, int globalRate, int globalBurst, int ticketRate, int ticketBurst) {
        return new TicketAdmissionLimiter(redis, new AdmissionLimitProperties(true, globalRate, globalBurst, ticketRate, ticketBurst, 2, namespace), new SimpleMeterRegistry());
    }
    String ownNamespace() { return "probe_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16); }
    String key(String namespace, String suffix) { return "ticketing:purchase-admission:v1:" + namespace + ":" + suffix; }
    int requestCount(long user) { return db.queryForObject("SELECT COUNT(*) FROM purchase_request WHERE user_id=?", Integer.class, user); }
    long capacity() { return db.queryForObject("SELECT pending_count FROM purchase_capacity WHERE id=1", Long.class); }
    Map<String,Object> body(int quantity) { return Map.of("skuId", Long.toString(sku), "quantity", quantity); }
    Client client() throws Exception {
        String username = "gate_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20), password = UUID.randomUUID().toString();
        long id = Long.parseLong(accounts.register(new RegisterRequest(username, password)).id());
        users.add(id);
        var client = new Client(id);
        client.csrf();
        var login = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                .header("X-CSRF-TOKEN", client.token).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=" + username + "&password=" + password)).build();
        assertThat(client.http.send(login, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        client.csrf();
        return client;
    }
    class Client {
        final long id;
        String token = "";
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        Client(long id) { this.id = id; }
        void csrf() throws Exception { token = json.readTree(send("GET", "/auth/csrf", null, null, false).body()).path("data").path("token").asText(); }
        JsonNode order(String key, int quantity, int expected) throws Exception {
            var response = send("POST", "/orders", body(quantity), key, true);
            assertThat(response.statusCode()).isEqualTo(expected);
            var result = json.readTree(response.body());
            return expected < 300 ? result.path("data") : result;
        }
        HttpResponse<String> send(String method, String path, Object body, String key, boolean csrf) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json");
            if (csrf) request.header("X-CSRF-TOKEN", token);
            if (key != null) request.header("Idempotency-Key", key);
            return http.send(request.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
