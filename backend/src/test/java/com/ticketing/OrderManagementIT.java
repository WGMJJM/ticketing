package com.ticketing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.auth.dto.RegisterRequest;
import com.ticketing.auth.service.AccountService;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import com.ticketing.order.mapper.OrderMapper;
import com.ticketing.order.entity.TicketOrder;
import com.ticketing.ticket.entity.TicketSku;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 所有并发通过真实HTTP进入独立事务和数据库连接，不在测试线程事务内模拟竞争。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.servlet.session.cookie.secure=false", "ticketing.admin-bootstrap.enabled=false",
                "ticketing.order.max-per-user-session=4", "ticketing.order.payment-window-minutes=15"})
class OrderManagementIT {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountService accounts;
    @MockitoSpyBean private OrderMapper orderMapper;
    private final List<Long> users = new ArrayList<>();
    private long activityId;
    private long sessionId;
    private long skuId;
    private Client buyer;
    private Client other;
    private Client admin;

    @BeforeEach
    void fixture() throws Exception {
        buyer = client(false);
        other = client(false);
        admin = client(true);
        String title = "订单验收" + UUID.randomUUID();
        jdbc.update("INSERT INTO activity(title,category,city,venue,status,published_at) VALUES(?,'CONCERT','上海','测试场馆','PUBLISHED',UTC_TIMESTAMP(6))", title);
        activityId = jdbc.queryForObject("SELECT id FROM activity WHERE title=?", Long.class, title);
        jdbc.update("INSERT INTO activity_session(activity_id,title,starts_at,ends_at,sale_starts_at) VALUES(?,'订单测试场次',DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 DAY),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 2 DAY),DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 DAY))", activityId);
        sessionId = jdbc.queryForObject("SELECT id FROM activity_session WHERE activity_id=?", Long.class, activityId);
        skuId = sku(8, 12345);
    }

    @AfterEach
    void cleanup() {
        if (sessionId != 0) {
            jdbc.update("DELETE i FROM ticket_order_item i JOIN ticket_order o ON i.order_id=o.id WHERE o.session_id=?", sessionId);
            jdbc.update("DELETE FROM ticket_order WHERE session_id=?", sessionId);
            jdbc.update("DELETE FROM ticket_sku WHERE session_id=?", sessionId);
            jdbc.update("DELETE FROM activity_session WHERE id=?", sessionId);
        }
        if (activityId != 0) jdbc.update("DELETE FROM activity WHERE id=?", activityId);
        for (Long userId : users) jdbc.update("DELETE FROM user_account WHERE id=?", userId);
    }

    @Test
    void createsRealOrderWithExactPriceSnapshotAndOwnList() throws Exception {
        JsonNode order = buyer.create(skuId, 2, "normal", 200).path("data");
        assertThat(order.path("status").asText()).isEqualTo("PENDING");
        assertThat(order.path("id").isTextual()).isTrue();
        assertThat(order.path("unitPriceCents").asText()).isEqualTo("12345");
        assertThat(order.path("totalAmountCents").asText()).isEqualTo("24690");
        assertThat(order.path("quantity").asInt()).isEqualTo(2);
        assertThat(order.path("paymentDeadlinePassed").asBoolean()).isFalse();
        assertThat(java.time.Duration.between(java.time.Instant.parse(order.path("createdAt").asText()),
                java.time.Instant.parse(order.path("expiresAt").asText())).toMinutes()).isEqualTo(15);
        invariant(2, 0);
        assertThat(buyer.call("GET", "/orders", null, null, 200).path("data").path("items").size()).isEqualTo(1);
        assertThat(other.call("GET", "/orders", null, null, 200).path("data").path("total").asInt()).isZero();
        assertThat(used(buyer)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT unit_price_cents*quantity FROM ticket_order_item WHERE order_id=?", Long.class, order.path("id").asLong())).isEqualTo(24690L);
        jdbc.update("UPDATE ticket_sku SET price_cents=99999,name='修改后的名称' WHERE id=?", skuId);
        JsonNode snapshot = buyer.call("GET", "/orders/" + order.path("id").asText(), null, null, 200).path("data");
        assertThat(snapshot.path("unitPriceCents").asText()).isEqualTo("12345");
        assertThat(snapshot.path("skuName").asText()).isEqualTo(order.path("skuName").asText());
    }

    @Test
    void invalidInputReadonlyFieldsHeadersAndOverflowDoNotReserve() throws Exception {
        for (String field : List.of("userId", "status", "totalAmountCents", "unitPriceCents", "lockedStock", "idempotencyOperation")) {
            var body = new HashMap<String, Object>(Map.of("skuId", Long.toString(skuId), "quantity", 1));
            body.put(field, "ADMIN");
            buyer.call("POST", "/orders", body, "invalid", 400);
        }
        for (Object quantity : List.of(0, -1, 1.5, 2147483648L)) {
            buyer.call("POST", "/orders", Map.of("skuId", Long.toString(skuId), "quantity", quantity), "invalid", 400);
        }
        buyer.call("POST", "/orders", Map.of("skuId", "9223372036854775808", "quantity", 1), "invalid", 400);
        buyer.call("POST", "/orders", Map.of("skuId", Long.toString(skuId), "quantity", 1), null, 400);
        buyer.create(skuId, 1, "bad key", 400);
        buyer.create(skuId, 1, "a".repeat(129), 400);
        buyer.create(skuId, 5, "too-many", 409);
        jdbc.update("UPDATE ticket_sku SET max_quantity_per_order=2 WHERE id=?", skuId);
        buyer.create(skuId, 3, "sku-limit", 409);
        jdbc.update("UPDATE ticket_sku SET price_cents=? WHERE id=?", Long.MAX_VALUE, skuId);
        buyer.create(skuId, 2, "overflow", 400);
        invariant(0, 0);
        assertThat(orderCount()).isZero();
        assertThat(used(buyer)).isZero();
    }

    @Test
    void insufficientStockAndOneWinnerForLastTicketAcrossRealConnections() throws Exception {
        jdbc.update("UPDATE ticket_sku SET total_stock=1 WHERE id=?", skuId);
        buyer.create(skuId, 2, "too-much-stock", 409);
        assertThat(orderCount()).isZero();
        List<Integer> results = parallel(2, index -> (index == 0 ? buyer : other).create(skuId, 1, "last-ticket", -1).path("httpStatus").asInt());
        assertThat(results).containsExactlyInAnyOrder(200, 409);
        invariant(1, 0);
        assertThat(orderCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT SUM(quantity) FROM ticket_order_item WHERE session_id=?", Integer.class, sessionId)).isEqualTo(1);
    }

    @Test
    void concurrentSameKeyReturnsOneOrderAndCancelledReplayNeverReservesAgain() throws Exception {
        List<String> results = parallel(6, index -> buyer.create(skuId, 2, "same-key", 200).path("data").path("id").asText());
        assertThat(new HashSet<>(results)).hasSize(1);
        assertThat(orderCount()).isEqualTo(1);
        invariant(2, 0);
        buyer.create(skuId, 1, "same-key", 409);
        long differentSku = sku(10, 22200);
        buyer.create(differentSku, 2, "same-key", 409);
        other.create(skuId, 1, "same-key", 200);
        assertThat(orderCount()).isEqualTo(2);
        buyer.call("POST", "/orders/" + results.getFirst() + "/cancel", null, null, 200);
        assertThat(buyer.create(skuId, 2, "same-key", 200).path("data").path("status").asText()).isEqualTo("CANCELLED");
        invariant(1, 0);
        assertThat(used(buyer)).isZero();
    }

    @Test
    void concurrentDifferentSkusShareOneUserSessionLimit() throws Exception {
        long secondSku = sku(20, 10000);
        var results = parallel(2, index -> buyer.create(index == 0 ? skuId : secondSku, 3, "quota-" + index, -1).path("httpStatus").asInt());
        assertThat(results).containsExactlyInAnyOrder(200, 409);
        assertThat(used(buyer)).isEqualTo(3);
        assertThat(orderCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT SUM(locked_stock) FROM ticket_sku WHERE session_id=?", Integer.class, sessionId)).isEqualTo(3);
        buyer.create(secondSku, 1, "quota-final", 200);
        buyer.create(skuId, 1, "quota-over", 409);
        assertThat(used(buyer)).isEqualTo(4);
        assertThat(buyer.call("GET", "/orders/limits?sessionId=" + sessionId, null, null, 200).path("data").path("remainingQuantity").asInt()).isZero();
    }

    @Test
    void failedItemWriteRollsBackStockOrderAndQuotaAndSameKeyCanRetry() throws Exception {
        // 只注入最后一步写入故障；前面的库存和订单SQL、HTTP及事务均为真实执行。
        doThrow(new DataIntegrityViolationException("验收注入明细写入失败")).when(orderMapper)
                .insertItem(any(TicketOrder.class), any(TicketSku.class), eq(2));
        try {
            buyer.create(skuId, 2, "retry-after-failure", 409);
            invariant(0, 0);
            assertThat(orderCount()).isZero();
            assertThat(used(buyer)).isZero();
        } finally { reset(orderMapper); }
        buyer.create(skuId, 2, "retry-after-failure", 200);
        invariant(2, 0);
        assertThat(orderCount()).isEqualTo(1);
        assertThat(used(buyer)).isEqualTo(2);
    }

    @Test
    void concurrentCancellationReleasesExactlyOnceAndCreateCancelRemainConsistent() throws Exception {
        String id = buyer.create(skuId, 4, "cancel-once", 200).path("data").path("id").asText();
        parallel(5, index -> buyer.call("POST", "/orders/" + id + "/cancel", null, null, 200));
        invariant(0, 0);
        assertThat(used(buyer)).isZero();
        var results = parallel(2, index -> index == 0 ? buyer.call("POST", "/orders/" + id + "/cancel", null, null, 200)
                : buyer.create(skuId, 4, "next-order", 200));
        assertThat(results).hasSize(2);
        invariant(4, 0);
        assertThat(used(buyer)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order WHERE session_id=? AND status='CANCELLED'", Integer.class, sessionId)).isEqualTo(1);
    }

    @Test
    void deadlineDoesNotPretendAutomaticCancellationAndCanStillBeManuallyCancelled() throws Exception {
        String id = buyer.create(skuId, 2, "expired-pending", 200).path("data").path("id").asText();
        jdbc.update("UPDATE ticket_order SET created_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 20 MINUTE),expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 MINUTE) WHERE id=?", Long.parseLong(id));
        JsonNode order = buyer.call("GET", "/orders/" + id, null, null, 200).path("data");
        assertThat(order.path("status").asText()).isEqualTo("PENDING");
        assertThat(order.path("paymentDeadlinePassed").asBoolean()).isTrue();
        assertThat(order.path("canCancel").asBoolean()).isTrue();
        invariant(2, 0);
        assertThat(used(buyer)).isEqualTo(2);
        buyer.call("POST", "/orders/" + id + "/cancel", null, null, 200);
        invariant(0, 0);
    }

    @Test
    void paidOccupiesQuotaButCancelledAndExpiredDoNot() throws Exception {
        String id = buyer.create(skuId, 3, "paid-fixture", 200).path("data").path("id").asText();
        // 仅模拟未来支付事实夹具，业务没有支付接口。
        jdbc.update("UPDATE ticket_order SET status='PAID',paid_at=UTC_TIMESTAMP(6) WHERE id=?", Long.parseLong(id));
        jdbc.update("UPDATE ticket_sku SET locked_stock=0,sold_stock=3 WHERE id=?", skuId);
        buyer.create(skuId, 2, "paid-limit", 409);
        buyer.call("POST", "/orders/" + id + "/cancel", null, null, 409);
        String pending = buyer.create(skuId, 1, "expires-fixture", 200).path("data").path("id").asText();
        jdbc.update("UPDATE ticket_order SET status='EXPIRED' WHERE id=?", Long.parseLong(pending));
        jdbc.update("UPDATE ticket_sku SET locked_stock=0 WHERE id=?", skuId);
        assertThat(used(buyer)).isEqualTo(3);
        buyer.create(skuId, 1, "after-expired", 200);
        invariant(1, 3);
    }

    @Test
    void ownershipAuthenticationDisabledAccountAndCsrfAreEnforced() throws Exception {
        String id = buyer.create(skuId, 1, "private", 200).path("data").path("id").asText();
        for (Client actor : List.of(other, admin)) {
            actor.call("GET", "/orders/" + id, null, null, 404);
            actor.call("POST", "/orders/" + id + "/cancel", null, null, 404);
        }
        Client anonymous = new Client();
        anonymous.call("GET", "/orders", null, null, 401);
        anonymous.csrf();
        anonymous.create(skuId, 1, "anonymous", 401);
        String token = buyer.token;
        buyer.token = "";
        buyer.create(skuId, 1, "no-csrf", 403);
        buyer.token = "wrong";
        buyer.call("POST", "/orders/" + id + "/cancel", null, null, 403);
        buyer.token = token;
        jdbc.update("UPDATE user_account SET status='DISABLED' WHERE id=?", buyer.id);
        buyer.create(skuId, 1, "disabled", 401);
        invariant(1, 0);
        assertThat(orderCount()).isEqualTo(1);
    }

    @Test
    void parentStatesAndSaleWindowAreAuthoritativeButCancellationStillWorks() throws Exception {
        String id = buyer.create(skuId, 1, "before-offline", 200).path("data").path("id").asText();
        jdbc.update("UPDATE activity SET status='OFFLINE' WHERE id=?", activityId);
        buyer.create(skuId, 1, "offline", 409);
        buyer.call("POST", "/orders/" + id + "/cancel", null, null, 200);
        jdbc.update("UPDATE activity SET status='PUBLISHED' WHERE id=?", activityId);
        jdbc.update("UPDATE activity_session SET status='CANCELLED' WHERE id=?", sessionId);
        buyer.create(skuId, 1, "cancelled-session", 409);
        jdbc.update("UPDATE activity_session SET status='SCHEDULED' WHERE id=?", sessionId);
        jdbc.update("UPDATE ticket_sku SET sale_starts_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 10 MINUTE) WHERE id=?", skuId);
        buyer.create(skuId, 1, "not-started", 409);
        jdbc.update("UPDATE ticket_sku SET sale_starts_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 HOUR),sale_ends_at=UTC_TIMESTAMP(6) WHERE id=?", skuId);
        buyer.create(skuId, 1, "ended-window", 409);
        jdbc.update("UPDATE ticket_sku SET sale_ends_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR),status='OFF_SALE' WHERE id=?", skuId);
        buyer.create(skuId, 1, "off-sku", 409);
        invariant(0, 0);
    }

    @Test
    void adminStockUpdateRacingPurchasePreservesInvariant() throws Exception {
        var results = parallel(2, index -> index == 0 ? buyer.create(skuId, 4, "admin-race", -1)
                : admin.call("PUT", "/admin/tickets/" + skuId, ticketBody(1, 0), null, -1));
        for (JsonNode result : results) assertThat(result.path("httpStatus").asInt()).isIn(200, 409);
        int total = jdbc.queryForObject("SELECT total_stock FROM ticket_sku WHERE id=?", Integer.class, skuId);
        int locked = jdbc.queryForObject("SELECT locked_stock FROM ticket_sku WHERE id=?", Integer.class, skuId);
        assertThat(total).isGreaterThanOrEqualTo(locked);
        assertThat(locked).isIn(0, 4);
        assertThat(orderCount()).isEqualTo(locked == 4 ? 1 : 0);
        assertThat(used(buyer)).isEqualTo(locked);
        invariant(locked, 0);
    }

    @Test
    void activityOfflineAndSessionCancellationRacesSerializeWithCreate() throws Exception {
        var results = parallel(2, index -> index == 0 ? buyer.create(skuId, 1, "offline-race", -1)
                : admin.call("POST", "/admin/activities/" + activityId + "/offline", Map.of("version", 0), null, 200));
        int created = results.get(0).path("httpStatus").asInt() == 200 ? 1 : 0;
        assertThat(results.get(0).path("httpStatus").asInt()).isIn(200, 409);
        buyer.create(skuId, 1, "after-offline", 409);
        invariant(created, 0);
        assertThat(orderCount()).isEqualTo(created);
        jdbc.update("UPDATE activity SET status='PUBLISHED' WHERE id=?", activityId);
        var next = parallel(2, index -> index == 0 ? other.create(skuId, 1, "cancel-race", -1)
                : admin.call("POST", "/admin/sessions/" + sessionId + "/cancel", Map.of("version", 0), null, 200));
        int second = next.get(0).path("httpStatus").asInt() == 200 ? 1 : 0;
        assertThat(next.get(0).path("httpStatus").asInt()).isIn(200, 409);
        other.create(skuId, 1, "after-session-cancel", 409);
        invariant(created + second, 0);
        assertThat(orderCount()).isEqualTo(created + second);
    }

    private Map<String, Object> ticketBody(int total, int version) {
        var row = jdbc.queryForMap("SELECT * FROM ticket_sku WHERE id=?", skuId);
        return Map.of("name", row.get("name"), "priceCents", row.get("price_cents").toString(), "totalStock", total,
                "saleStartsAt", ((java.time.LocalDateTime) row.get("sale_starts_at")).toInstant(java.time.ZoneOffset.UTC).toString(),
                "saleEndsAt", ((java.time.LocalDateTime) row.get("sale_ends_at")).toInstant(java.time.ZoneOffset.UTC).toString(),
                "maxQuantityPerOrder", 4, "version", version);
    }
    private long sku(int stock, long price) {
        String name = "验收票" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO ticket_sku(session_id,name,price_cents,total_stock,sale_starts_at,sale_ends_at,status) VALUES(?,?,?, ?,DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 HOUR),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR),'ON_SALE')", sessionId, name, price, stock);
        return jdbc.queryForObject("SELECT id FROM ticket_sku WHERE session_id=? AND name=?", Long.class, sessionId, name);
    }
    private int orderCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order WHERE session_id=?", Integer.class, sessionId); }
    private int used(Client client) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(i.quantity),0) FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id WHERE o.user_id=? AND o.session_id=? AND o.status IN('PENDING','PAID')", Integer.class, client.id, sessionId);
    }
    private void invariant(int locked, int sold) {
        var row = jdbc.queryForMap("SELECT total_stock,locked_stock,sold_stock FROM ticket_sku WHERE id=?", skuId);
        assertThat(((Number) row.get("locked_stock")).intValue()).isEqualTo(locked);
        assertThat(((Number) row.get("sold_stock")).intValue()).isEqualTo(sold);
        assertThat(locked).isGreaterThanOrEqualTo(0);
        assertThat(sold).isGreaterThanOrEqualTo(0);
        assertThat(locked + sold).isLessThanOrEqualTo(((Number) row.get("total_stock")).intValue());
    }
    private Client client(boolean administrator) throws Exception {
        Client client = new Client();
        String username = "order_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String password = UUID.randomUUID().toString();
        client.csrf();
        if (administrator) accounts.initializeAdmin(new RegisterRequest(username, password));
        else client.call("POST", "/auth/register", Map.of("username", username, "password", password), null, 200);
        client.id = jdbc.queryForObject("SELECT id FROM user_account WHERE username=?", Long.class, username);
        users.add(client.id);
        client.raw("POST", "/auth/login", "username=" + username + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8), "application/x-www-form-urlencoded", null, 200);
        client.csrf();
        return client;
    }
    private <T> List<T> parallel(int count, CheckedFunction<T> operation) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(count)) {
            List<Future<T>> futures = new ArrayList<>();
            for (int index = 0; index < count; index++) { int value = index; futures.add(pool.submit(() -> { start.await(); return operation.apply(value); })); }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get(25, TimeUnit.SECONDS));
            return results;
        }
    }
    @FunctionalInterface private interface CheckedFunction<T> { T apply(int index) throws Exception; }

    private class Client {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();
        long id;
        String token = "";
        void csrf() throws Exception { token = call("GET", "/auth/csrf", null, null, 200).path("data").path("token").asText(); }
        JsonNode create(long sku, int quantity, String key, int expected) throws Exception {
            return call("POST", "/orders", Map.of("skuId", Long.toString(sku), "quantity", quantity), key, expected);
        }
        JsonNode call(String method, String path, Object body, String key, int expected) throws Exception {
            return raw(method, path, body == null ? "" : json.writeValueAsString(body), "application/json", key, expected);
        }
        JsonNode raw(String method, String path, String body, String type, String key, int expected) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                    .timeout(java.time.Duration.ofSeconds(20)).header("Content-Type", type);
            if (!token.isEmpty()) request.header("X-CSRF-TOKEN", token);
            if (key != null) request.header("Idempotency-Key", key);
            var response = http.send(request.method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            if (expected >= 0) assertThat(response.statusCode()).as("%s %s %s", method, path, response.body()).isEqualTo(expected);
            var result = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(response.body());
            result.put("httpStatus", response.statusCode());
            return result;
        }
    }
}
