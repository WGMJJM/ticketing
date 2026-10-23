package com.ticketing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.auth.RateLimitProperties;
import com.ticketing.auth.RateLimitProperties.Policy;
import com.ticketing.auth.RegisterRequest;
import com.ticketing.auth.RedisRateLimiter;
import com.ticketing.auth.RedisRateLimiter.Outcome;
import com.ticketing.auth.AccountService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/** 真实Redis Lua和真实HTTP/Session/CSRF；只扫描并清理本测试的随机命名空间。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.servlet.session.cookie.secure=false", "ticketing.admin-bootstrap.enabled=false",
        "ticketing.security.rate-limit.enabled=true", "ticketing.security.rate-limit.login-ip.limit=6",
        "ticketing.security.rate-limit.login-ip.window-seconds=3",
        "ticketing.security.rate-limit.login-account-ip.limit=2",
        "ticketing.security.rate-limit.login-account-ip.window-seconds=3",
        "ticketing.security.rate-limit.register-ip.limit=2", "ticketing.security.rate-limit.register-ip.window-seconds=1",
        "ticketing.security.rate-limit.order-user.limit=2", "ticketing.security.rate-limit.order-user.window-seconds=1",
        "ticketing.security.rate-limit.waitlist-user.limit=1", "ticketing.security.rate-limit.waitlist-user.window-seconds=1"})
class RateLimitIT {
    private static final String NAMESPACE = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    private static final String SECRET = secret();
    private static final String PREFIX = "ticketing:rate-limit:v1:" + NAMESPACE + ":";
    @DynamicPropertySource
    static void redisSettings(DynamicPropertyRegistry registry) {
        registry.add("ticketing.security.rate-limit.namespace", () -> NAMESPACE);
        registry.add("ticketing.security.rate-limit.key-secret", () -> SECRET);
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> System.getenv().getOrDefault("RATE_LIMIT_TEST_REDIS_PORT", "16379"));
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountService accounts;
    @Autowired com.ticketing.trade.PurchaseOrderWorker purchaseWorker;
    @Autowired com.ticketing.admission.PurchaseRequestMapper purchaseRequests;
    @Autowired RedisRateLimiter limiter;
    @Autowired RateLimitProperties properties;
    @MockitoSpyBean StringRedisTemplate redis;
    private final List<Long> users = new ArrayList<>();
    private final String password = UUID.randomUUID().toString();
    private long activityId, sessionId, skuId;

    @BeforeEach
    void clearCounters() {
        reset(redis);
        clearOwnNamespace();
    }

    @AfterEach
    void cleanup() {
        reset(redis);
        clearOwnNamespace();
        if (sessionId != 0) {
            PurchaseTestSupport.cleanup(jdbc,sessionId);
            jdbc.update("DELETE FROM waitlist_entry WHERE sku_id=?", skuId);
            jdbc.update("DELETE i FROM ticket_order_item i JOIN ticket_order o ON o.id=i.order_id WHERE o.session_id=?", sessionId);
            jdbc.update("DELETE FROM ticket_order WHERE session_id=?", sessionId);
            jdbc.update("DELETE FROM ticket_sku WHERE session_id=?", sessionId);
            jdbc.update("DELETE FROM activity_session WHERE id=?", sessionId);
            jdbc.update("DELETE FROM activity WHERE id=?", activityId);
        }
        for (Long id : users) jdbc.update("DELETE FROM user_account WHERE id=?", id);
    }

    void clearOwnNamespace() {
        try (var cursor = redis.scan(ScanOptions.scanOptions().match(PREFIX + "*").count(100).build())) {
            while (cursor.hasNext()) redis.delete(cursor.next());
        }
    }

    @Test
    void registrationPreservesJsonBodyAndRecoversAfterTtl() throws Exception {
        Browser browser = new Browser();
        for (int i = 0; i < 2; i++) {
            var response = browser.post("/auth/register", Map.of("username", name(), "password", password), null, 200);
            users.add(body(response).path("data").path("id").asLong());
        }
        long count = jdbc.queryForObject("SELECT COUNT(*) FROM user_account", Long.class);
        rejection(browser.post("/auth/register", Map.of("username", name(), "password", password), null, 429), 429);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_account", Long.class)).isEqualTo(count);
        Thread.sleep(1100);
        var response = browser.post("/auth/register", Map.of("username", name(), "password", password), null, 200);
        users.add(body(response).path("data").path("id").asLong());
    }

    @Test
    void actualFormLoginCountsSuccessAndFailureAndDoesNotResetOnSuccess() throws Exception {
        String username = createAccount();
        Browser browser = new Browser();
        String previousCookie = browser.cookie();
        assertThat(body(browser.login(username.toUpperCase(), "wrong-password", 401)).path("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        browser.login(username, password, 200);
        assertThat(browser.cookie()).isNotEqualTo(previousCookie);
        browser.csrf();
        assertThat(body(browser.get("/auth/me", 200)).path("data").path("username").asText()).isEqualTo(username);
        rejection(browser.login(username, password, 429), 429);
        browser.post("/auth/logout", null, null, 200);
        browser.csrf();
        browser.get("/auth/me", 401);
        rejection(browser.login(username, "wrong-password", 429), 429);
    }

    @Test
    void unknownAndWrongPasswordHaveSameErrorAndCsrfCannotBeSkipped() throws Exception {
        String username = createAccount();
        Browser browser = new Browser();
        var wrong = body(browser.login(username, "wrong-password", 401));
        var absent = body(browser.login(name(), "wrong-password", 401));
        assertThat(wrong.path("code")).isEqualTo(absent.path("code"));
        assertThat(wrong.path("message")).isEqualTo(absent.path("message"));
        clearOwnNamespace();
        String valid = browser.token;
        browser.token = "";
        for (String path : List.of("/auth/login", "/auth/register", "/orders", "/waitlist"))
            browser.post(path, Map.of(), null, 403);
        assertThat(ownKeys()).isEmpty();
        browser.token = valid;
        browser.login(username, password, 200);
        browser.csrf();
        // 登录前的CSRF令牌不能用于登录后的写入。
        browser.token = valid;
        browser.post("/orders", Map.of(), "old-token", 403);
        assertThat(ownKeys()).noneMatch(key -> key.contains(":order-user:"));
    }

    @Test
    void pairFairnessIpBudgetAndCaseNormalizationAreAtomic() {
        assertThat(limiter.login("192.0.2.10", " Alice ").outcome()).isEqualTo(Outcome.ALLOWED);
        assertThat(limiter.login("192.0.2.10", "ALICE").outcome()).isEqualTo(Outcome.ALLOWED);
        assertThat(limiter.login("192.0.2.10", "alice").outcome()).isEqualTo(Outcome.LIMITED);
        assertThat(limiter.login("192.0.2.10", "bob").outcome()).isEqualTo(Outcome.ALLOWED);
        assertThat(limiter.login("192.0.2.11", "alice").outcome()).isEqualTo(Outcome.ALLOWED);
        for (int i = 0; i < 3; i++) assertThat(limiter.login("192.0.2.10", "different" + i).outcome()).isEqualTo(Outcome.ALLOWED);
        assertThat(limiter.login("192.0.2.10", "another").outcome()).isEqualTo(Outcome.LIMITED);
        assertThat(limiter.login("192.0.2.12", "alice").outcome()).isEqualTo(Outcome.ALLOWED);
        assertThat(ownKeys()).allMatch(key -> key.matches(PREFIX + "[a-z-]+:[0-9a-f]{64}"));
        assertThat(ownKeys()).noneMatch(key -> key.contains("alice") || key.contains("192.0.2"));
        for (String key : ownKeys()) assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, 3000L);
    }

    @Test
    void forgedForwardingHeadersCannotBypassConnectionIpBudget() throws Exception {
        Browser browser = new Browser();
        for (int i = 0; i < 6; i++) browser.login(name(), "wrong-password", 401, "198.51.100." + i);
        rejection(browser.login(name(), "wrong-password", 429, "203.0.113.9"), 429);
        assertThat(ownKeys().stream().filter(key -> key.contains(":login-ip:"))).hasSize(1);
    }

    @Test
    void twoLimiterInstancesAllowExactly50Of160ConcurrentRequests() throws Exception {
        var policy = new Policy(50, 10);
        var settings = new RateLimitProperties(true, SECRET, NAMESPACE, 1, policy, policy, policy, policy, policy, policy);
        var first = new RedisRateLimiter(redis, settings, new SimpleMeterRegistry());
        var second = new RedisRateLimiter(new StringRedisTemplate(redis.getConnectionFactory()), settings, new SimpleMeterRegistry());
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(32)) {
            var results = new ArrayList<java.util.concurrent.Future<Outcome>>();
            for (int i = 0; i < 160; i++) {
                var instance = i % 2 == 0 ? first : second;
                results.add(pool.submit(() -> { start.await(); return instance.registration("192.0.2.1").outcome(); }));
            }
            start.countDown();
            int allowed = 0, limited = 0;
            for (var result : results) {
                Outcome outcome = result.get(15, TimeUnit.SECONDS);
                if (outcome == Outcome.ALLOWED) allowed++;
                if (outcome == Outcome.LIMITED) limited++;
            }
            assertThat(allowed).isEqualTo(50);
            assertThat(limited).isEqualTo(110);
        }
        assertThat(redis.opsForValue().get(ownKeys().getFirst())).isEqualTo("50");
    }

    @Test
    void ordersRejectBeforeWritesAndKeepIdempotencyAfterWindowRecovery() throws Exception {
        fixture(20);
        Browser buyer = loggedIn();
        Map<String, Object> request = Map.of("skuId", Long.toString(skuId), "quantity", 1);
        String id = body(buyer.post("/orders", request, "same-key", 202)).path("data").path("requestId").asText();
        assertThat(body(buyer.post("/orders", request, "same-key", 202)).path("data").path("requestId").asText()).isEqualTo(id);
        rejection(buyer.post("/orders", request, "new-key", 429), 429);
        assertFacts(0, 0, 0);
        PurchaseTestSupport.order(purchaseWorker,purchaseRequests,jdbc,Long.parseLong(id));
        assertFacts(1, 1, 0);
        Thread.sleep(1100);
        assertThat(body(buyer.post("/orders", request, "same-key", 200)).path("data").path("requestId").asText()).isEqualTo(id);
        long second=body(buyer.post("/orders", request, "new-key", 202)).path("data").path("requestId").asLong();
        PurchaseTestSupport.order(purchaseWorker,purchaseRequests,jdbc,second);
        assertFacts(2, 2, 0);
    }

    @Test
    void waitlistRejectsBeforeWritesAndOtherUserHasIndependentBudget() throws Exception {
        fixture(0);
        Browser first = loggedIn(), second = loggedIn();
        Map<String, Object> request = Map.of("skuId", Long.toString(skuId), "quantity", 1);
        String id = body(first.post("/waitlist", request, null, 200)).path("data").path("id").asText();
        rejection(first.post("/waitlist", request, null, 429), 429);
        assertFacts(0, 0, 1);
        second.post("/waitlist", request, null, 200);
        assertFacts(0, 0, 2);
        Thread.sleep(1100);
        assertThat(body(first.post("/waitlist", request, null, 200)).path("data").path("id").asText()).isEqualTo(id);
        assertFacts(0, 0, 2);
    }

    @Test
    void authenticationStatusRoleAndCsrfRunBeforeBusinessLimiter() throws Exception {
        Browser anonymous = new Browser();
        anonymous.post("/orders", Map.of(), "anonymous", 401);
        anonymous.post("/waitlist", Map.of(), null, 401);
        assertThat(ownKeys()).isEmpty();
        String username = createAccount();
        Browser staff = new Browser();
        staff.login(username, password, 200);
        staff.csrf();
        jdbc.update("UPDATE user_account SET role='CHECKIN_STAFF' WHERE username=?", username);
        staff.get("/checkins/sessions", 200);
        staff.get("/admin/audit-logs", 403);
        staff.post("/admin/checkins", Map.of("credential", "invalid"), null, 403);
        jdbc.update("UPDATE user_account SET role='USER' WHERE username=?", username);
        staff.get("/checkins/sessions", 403);
        jdbc.update("UPDATE user_account SET status='DISABLED' WHERE username=?", username);
        staff.post("/orders", Map.of(), "disabled", 401);
        assertThat(ownKeys()).noneMatch(key -> key.contains(":order-user:") || key.contains(":waitlist-user:"));
        staff.get("/auth/me", 401);
    }

    @Test
    void redisFailureReturns503OnlyForProtectedSubmissionsAndNeverWrites() throws Exception {
        fixture(20);
        Browser buyer = loggedIn();
        doThrow(new DataAccessResourceFailureException("test-only")).when(redis)
                .execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), any(Object[].class));
        rejection(buyer.post("/orders", Map.of("skuId", Long.toString(skuId), "quantity", 1), "outage", 503), 503);
        rejection(buyer.post("/waitlist", Map.of("skuId", Long.toString(skuId), "quantity", 1), null, 503), 503);
        rejection(buyer.login(name(), password, 503), 503);
        rejection(buyer.post("/auth/register", Map.of("username", name(), "password", password), null, 503), 503);
        buyer.get("/activities", 200);
        buyer.get("/activities/" + activityId + "/sessions/" + sessionId + "/tickets", 200);
        buyer.get("/orders", 200);
        assertFacts(0, 0, 0);
        reset(redis);
        long accepted=body(buyer.post("/orders", Map.of("skuId", Long.toString(skuId), "quantity", 1), "outage", 202)).path("data").path("requestId").asLong();
        PurchaseTestSupport.order(purchaseWorker,purchaseRequests,jdbc,accepted);
        assertFacts(1, 1, 0);
    }

    @Test
    void realUnavailableRedisConnectionFailsClosedWithinBoundedTime() throws Exception {
        try (var unusedPort = new java.net.ServerSocket(0)) {
            int closedPort = unusedPort.getLocalPort();
            unusedPort.close();
            var config = new RedisStandaloneConfiguration("127.0.0.1", closedPort);
            var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(200))
                    .clientOptions(io.lettuce.core.ClientOptions.builder().socketOptions(
                            io.lettuce.core.SocketOptions.builder().connectTimeout(Duration.ofMillis(200)).build()).build()).build();
            var factory = new LettuceConnectionFactory(config, client);
            try {
                factory.afterPropertiesSet();
                factory.start();
                var unavailable = new RedisRateLimiter(new StringRedisTemplate(factory), properties, new SimpleMeterRegistry());
                long started = System.nanoTime();
                var decision = unavailable.registration("192.0.2.1");
                assertThat(decision.outcome()).isEqualTo(Outcome.UNAVAILABLE);
                assertThat(decision.retryAfterSeconds()).isEqualTo(1);
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
            } finally { factory.destroy(); }
        }
    }

    private List<String> ownKeys() {
        var keys = new ArrayList<String>();
        try (var cursor = redis.scan(ScanOptions.scanOptions().match(PREFIX + "*").count(100).build())) {
            // 本类只核对第十二阶段五类预算，新会话预算由CsrfSessionIT独立验证。
            while (cursor.hasNext()) {
                String key = cursor.next();
                if (!key.contains(":csrf-session-ip:")) keys.add(key);
            }
        }
        return keys;
    }

    private void fixture(int stock) {
        String title = "限流验收" + UUID.randomUUID();
        jdbc.update("INSERT INTO activity(title,category,city,venue,status,published_at) VALUES(?,'CONCERT','上海','限流验收场馆','PUBLISHED',UTC_TIMESTAMP(6))", title);
        activityId = jdbc.queryForObject("SELECT id FROM activity WHERE title=?", Long.class, title);
        jdbc.update("INSERT INTO activity_session(activity_id,title,starts_at,ends_at,sale_starts_at) VALUES(?,'限流验收场次',TIMESTAMPADD(DAY,1,UTC_TIMESTAMP(6)),TIMESTAMPADD(DAY,2,UTC_TIMESTAMP(6)),TIMESTAMPADD(DAY,-1,UTC_TIMESTAMP(6)))", activityId);
        sessionId = jdbc.queryForObject("SELECT id FROM activity_session WHERE activity_id=?", Long.class, activityId);
        jdbc.update("INSERT INTO ticket_sku(session_id,name,price_cents,total_stock,sale_starts_at,sale_ends_at,status) VALUES(?,'限流验收票',12800,?,TIMESTAMPADD(HOUR,-1,UTC_TIMESTAMP(6)),TIMESTAMPADD(HOUR,2,UTC_TIMESTAMP(6)),'ON_SALE')", sessionId, stock);
        skuId = jdbc.queryForObject("SELECT id FROM ticket_sku WHERE session_id=?", Long.class, sessionId);
    }

    private void assertFacts(int orders, int locked, int waiting) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order WHERE session_id=?", Integer.class, sessionId)).isEqualTo(orders);
        assertThat(jdbc.queryForObject("SELECT locked_stock FROM ticket_sku WHERE id=?", Integer.class, skuId)).isEqualTo(locked);
        assertThat(jdbc.queryForObject("SELECT sold_stock FROM ticket_sku WHERE id=?", Integer.class, skuId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM waitlist_entry WHERE sku_id=?", Integer.class, skuId)).isEqualTo(waiting);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_record p JOIN ticket_order o ON o.id=p.order_id WHERE o.session_id=?", Integer.class, sessionId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM electronic_ticket t JOIN ticket_order o ON o.id=t.order_id WHERE o.session_id=?", Integer.class, sessionId)).isZero();
    }

    private String createAccount() {
        String username = name();
        users.add(Long.valueOf(accounts.register(new RegisterRequest(username, password)).id()));
        return username;
    }
    private Browser loggedIn() throws Exception {
        Browser browser = new Browser();
        browser.login(createAccount(), password, 200);
        browser.csrf();
        return browser;
    }
    private String name() { return "limit_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24); }
    private JsonNode body(HttpResponse<String> response) throws Exception { return json.readTree(response.body()); }
    private void rejection(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(body(response).path("code").asText()).isEqualTo(status == 429 ? "TOO_MANY_REQUESTS" : "RATE_LIMIT_UNAVAILABLE");
        assertThat(response.headers().firstValue("Retry-After").map(Long::valueOf).orElse(0L)).isBetween(1L, 3L);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    }
    private static String secret() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private class Browser {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();
        String token;
        Browser() throws Exception { csrf(); }
        String cookie() { return cookies.getCookieStore().getCookies().stream().filter(cookie -> "JSESSIONID".equals(cookie.getName())).findFirst().orElseThrow().getValue(); }
        void csrf() throws Exception { token = body(get("/auth/csrf", 200)).path("data").path("token").asText(); }
        HttpResponse<String> get(String path, int status) throws Exception { return send("GET", path, null, null, null, status, null); }
        HttpResponse<String> post(String path, Object data, String idem, int status) throws Exception {
            return send("POST", path, data == null ? "" : json.writeValueAsString(data), "application/json", idem, status, null);
        }
        HttpResponse<String> login(String username, String pass, int status) throws Exception { return login(username, pass, status, null); }
        HttpResponse<String> login(String username, String pass, int status, String forwarded) throws Exception {
            return send("POST", "/auth/login", "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8) + "&password=" + URLEncoder.encode(pass, StandardCharsets.UTF_8),
                    "application/x-www-form-urlencoded", null, status, forwarded);
        }
        HttpResponse<String> send(String method, String path, String data, String type, String idem, int status, String forwarded) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path)).timeout(Duration.ofSeconds(10));
            if (token != null && !token.isEmpty()) request.header("X-CSRF-TOKEN", token);
            if (type != null) request.header("Content-Type", type);
            if (idem != null) request.header("Idempotency-Key", idem);
            if (forwarded != null) request.header("X-Forwarded-For", forwarded).header("X-Real-IP", forwarded).header("Forwarded", "for=" + forwarded);
            var response = http.send(request.method(method, data == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(data)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("%s %s", method, path).isEqualTo(status);
            assertThat(response.body()).doesNotContain(password, "passwordHash", "JSESSIONID", "stackTrace");
            return response;
        }
    }
}
