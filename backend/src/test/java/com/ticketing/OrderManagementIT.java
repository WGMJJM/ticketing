package com.ticketing;

import com.ticketing.config.OrderProperties;
import com.ticketing.dto.RegisterRequest;
import com.ticketing.dto.TicketSale;
import com.ticketing.entity.PaymentRecord;
import com.ticketing.entity.TicketOrder;
import com.ticketing.entity.TicketSku;
import com.ticketing.mapper.OrderMapper;
import com.ticketing.mapper.PaymentMapper;
import com.ticketing.mapper.PurchaseRequestMapper;
import com.ticketing.mapper.TicketSkuMapper;
import com.ticketing.service.AccountService;
import com.ticketing.service.OrderService;
import com.ticketing.service.PurchaseOrderWorker;
import com.ticketing.task.OrderExpiryJob;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
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
                "ticketing.order.max-per-user-session=4", "ticketing.order.payment-window-minutes=15",
                "ticketing.order.expiry.enabled=false"})
class OrderManagementIT {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountService accounts;
    @MockitoSpyBean private OrderMapper orderMapper;
    @MockitoSpyBean private TicketSkuMapper inventory;
    @MockitoSpyBean private PaymentMapper paymentMapper;
    @Autowired private OrderService orderService;
    @Autowired private com.ticketing.service.PurchaseOrderWorker purchaseWorker;
    @Autowired private com.ticketing.mapper.PurchaseRequestMapper purchaseRequests;
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
            PurchaseTestSupport.cleanup(jdbc, sessionId);
            jdbc.update("DELETE e FROM outbox_event e JOIN ticket_sku s ON e.aggregate_id=s.id WHERE e.aggregate_type='TICKET_SKU' AND s.session_id=?",sessionId);
            jdbc.update("DELETE t FROM electronic_ticket t JOIN ticket_order o ON t.order_id=o.id WHERE o.session_id=?", sessionId);
            jdbc.update("DELETE p FROM payment_record p JOIN ticket_order o ON p.order_id=o.id WHERE o.session_id=?", sessionId);
            jdbc.update("DELETE i FROM ticket_order_item i JOIN ticket_order o ON i.order_id=o.id WHERE o.session_id=?", sessionId);
            jdbc.update("DELETE FROM ticket_order WHERE session_id=?", sessionId);
            jdbc.update("DELETE FROM ticket_sku WHERE session_id=?", sessionId);
            jdbc.update("DELETE FROM activity_session WHERE id=?", sessionId);
        }
        if (activityId != 0) jdbc.update("DELETE FROM activity WHERE id=?", activityId);
        for (Long userId : users) jdbc.update("DELETE FROM user_account WHERE id=? AND id NOT IN (SELECT operator_id FROM admin_audit_log)", userId);
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
        List<String> results = parallel(2, index -> (index == 0 ? buyer : other).create(skuId, 1, "last-ticket", -1).path("creationOutcome").asText());
        assertThat(results).containsExactlyInAnyOrder("SUCCEEDED", "FAILED");
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
        var results = parallel(2, index -> buyer.create(index == 0 ? skuId : secondSku, 3, "quota-" + index, -1).path("creationOutcome").asText());
        assertThat(results).containsExactlyInAnyOrder("SUCCEEDED", "FAILED");
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
                .insertItem(any(TicketOrder.class), any(TicketSale.class), eq(2));
        long requestId = buyer.call("POST", "/orders", Map.of("skuId", Long.toString(skuId), "quantity", 2), "retry-after-failure", 202).path("data").path("requestId").asLong();
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> purchaseWorker.process(requestId))
                    .isInstanceOf(DataIntegrityViolationException.class);
            invariant(0, 0);
            assertThat(orderCount()).isZero();
            assertThat(used(buyer)).isZero();
        } finally { reset(orderMapper, inventory); }
        jdbc.update("UPDATE purchase_request SET processing_lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE id=?",requestId);
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
    void deadlineBlocksPaymentWhileAwaitingScanAndAllowsManualCancellation() throws Exception {
        String id = buyer.create(skuId, 2, "expired-pending", 200).path("data").path("id").asText();
        jdbc.update("UPDATE ticket_order SET created_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 20 MINUTE),expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 MINUTE) WHERE id=?", Long.parseLong(id));
        JsonNode order = buyer.call("GET", "/orders/" + id, null, null, 200).path("data");
        assertThat(order.path("status").asText()).isEqualTo("PENDING");
        assertThat(order.path("paymentDeadlinePassed").asBoolean()).isTrue();
        assertThat(order.path("canCancel").asBoolean()).isTrue();
        assertThat(order.path("canPay").asBoolean()).isFalse();
        buyer.call("POST", "/orders/" + id + "/pay", null, null, 409);
        invariant(2, 0);
        assertThat(used(buyer)).isEqualTo(2);
        buyer.call("POST", "/orders/" + id + "/cancel", null, null, 200);
        invariant(0, 0);
    }

    @Test
    void paidOccupiesQuotaButCancelledAndExpiredDoNot() throws Exception {
        String id = buyer.create(skuId, 3, "paid-fixture", 200).path("data").path("id").asText();
        buyer.call("POST", "/orders/" + id + "/pay", null, null, 200);
        buyer.create(skuId, 2, "paid-limit", 409);
        buyer.call("POST", "/orders/" + id + "/cancel", null, null, 409);
        String pending = buyer.create(skuId, 1, "expires-fixture", 200).path("data").path("id").asText();
        makeDue(pending);
        assertThat(orderService.expire(buyer.id, Long.parseLong(pending))).isTrue();
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
        int created = results.get(0).path("creationOutcome").asText().equals("SUCCEEDED") ? 1 : 0;
        assertThat(results.get(0).path("creationOutcome").asText()).isIn("SUCCEEDED", "FAILED");
        buyer.create(skuId, 1, "after-offline", 409);
        invariant(created, 0);
        assertThat(orderCount()).isEqualTo(created);
        jdbc.update("UPDATE activity SET status='PUBLISHED' WHERE id=?", activityId);
        var next = parallel(2, index -> index == 0 ? other.create(skuId, 1, "cancel-race", -1)
                : admin.call("POST", "/admin/sessions/" + sessionId + "/cancel", Map.of("version", 0), null, 200));
        int second = next.get(0).path("creationOutcome").asText().equals("SUCCEEDED") ? 1 : 0;
        assertThat(next.get(0).path("creationOutcome").asText()).isIn("SUCCEEDED", "FAILED");
        other.create(skuId, 1, "after-session-cancel", 409);
        invariant(created + second, 0);
        assertThat(orderCount()).isEqualTo(created + second);
    }

    @Test
    void simulatedPaymentUsesSnapshotAndConcurrentRetriesReturnOneRecord() throws Exception {
        String id = buyer.create(skuId, 2, "payment", 200).path("data").path("id").asText();
        buyer.call("GET", "/orders/" + id + "/payment", null, null, 404);
        jdbc.update("UPDATE ticket_sku SET price_cents=99999 WHERE id=?", skuId);
        var results = parallel(6, index -> buyer.call("POST", "/orders/" + id + "/pay", null, null, 200).path("data"));
        assertThat(results.stream().map(value -> value.path("paymentNo").asText()).distinct().count()).isEqualTo(1);
        JsonNode payment = results.getFirst();
        assertThat(payment.path("amountCents").asText()).isEqualTo("24690");
        assertThat(payment.path("simulated").asBoolean()).isTrue();
        assertThat(payment.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(buyer.call("GET", "/orders/" + id + "/payment", null, null, 200).path("data")).isEqualTo(payment);
        assertThat(paymentCount()).isEqualTo(1);
        invariant(0, 2);
        assertThat(used(buyer)).isEqualTo(2);
        JsonNode order = buyer.call("GET", "/orders/" + id, null, null, 200).path("data");
        assertThat(order.path("status").asText()).isEqualTo("PAID");
        assertThat(order.path("canPay").asBoolean()).isFalse();
        assertThat(order.path("canCancel").asBoolean()).isFalse();
        assertThat(order.path("payment")).isEqualTo(payment);
        assertThat(jdbc.queryForObject("SELECT paid_at FROM ticket_order WHERE id=?", java.time.LocalDateTime.class, Long.parseLong(id)))
                .isEqualTo(jdbc.queryForObject("SELECT paid_at FROM payment_record WHERE order_id=?", java.time.LocalDateTime.class, Long.parseLong(id)));
        jdbc.update("UPDATE activity SET status='OFFLINE' WHERE id=?", activityId);
        assertThat(buyer.call("POST", "/orders/" + id + "/pay", null, null, 200).path("data")).isEqualTo(payment);
        buyer.call("POST", "/orders/" + id + "/cancel", null, null, 409);
        assertThat(orderService.expire(buyer.id, Long.parseLong(id))).isFalse();
        invariant(0, 2);
    }

    @Test
    void paymentRejectsForeignOwnersAnonymousDisabledCsrfAndClientAmounts() throws Exception {
        String id = buyer.create(skuId, 1, "payment-private", 200).path("data").path("id").asText();
        for (Client actor : List.of(other, admin)) {
            actor.call("POST", "/orders/" + id + "/pay", null, null, 404);
            actor.call("GET", "/orders/" + id + "/payment", null, null, 404);
        }
        Client anonymous = new Client();
        anonymous.csrf();
        anonymous.call("POST", "/orders/" + id + "/pay", null, null, 401);
        anonymous.call("GET", "/orders/" + id + "/payment", null, null, 401);
        String token = buyer.token;
        buyer.token = "";
        buyer.call("POST", "/orders/" + id + "/pay", null, null, 403);
        buyer.token = "incorrect";
        buyer.call("POST", "/orders/" + id + "/pay", null, null, 403);
        buyer.token = token;
        buyer.call("POST", "/orders/" + id + "/pay", Map.of("amountCents", "1", "userId", other.id), null, 400);
        jdbc.update("UPDATE user_account SET status='DISABLED' WHERE id=?", buyer.id);
        buyer.call("POST", "/orders/" + id + "/pay", null, null, 401);
        invariant(1, 0);
        assertThat(paymentCount()).isZero();
    }

    @Test
    void paymentRejectsCancelledExpiredAndUnavailableParentsButCanPayHeldOfflineSku() throws Exception {
        String id = buyer.create(skuId, 1, "payment-states", 200).path("data").path("id").asText();
        jdbc.update("UPDATE activity SET status='OFFLINE' WHERE id=?", activityId);
        buyer.call("POST", "/orders/" + id + "/pay", null, null, 409);
        assertThat(buyer.call("GET", "/orders/" + id, null, null, 200).path("data").path("canPay").asBoolean()).isFalse();
        jdbc.update("UPDATE activity SET status='PUBLISHED' WHERE id=?", activityId);
        jdbc.update("UPDATE activity_session SET status='CANCELLED' WHERE id=?", sessionId);
        buyer.call("POST", "/orders/" + id + "/pay", null, null, 409);
        jdbc.update("UPDATE activity_session SET status='SCHEDULED' WHERE id=?", sessionId);
        buyer.call("POST", "/orders/" + id + "/cancel", null, null, 200);
        buyer.call("POST", "/orders/" + id + "/pay", null, null, 409);
        assertThat(orderService.expire(buyer.id, Long.parseLong(id))).isFalse();
        String expired = buyer.create(skuId, 1, "payment-expired", 200).path("data").path("id").asText();
        makeDue(expired);
        buyer.call("POST", "/orders/" + expired + "/pay", null, null, 409);
        assertThat(orderService.expire(buyer.id, Long.parseLong(expired))).isTrue();
        buyer.call("POST", "/orders/" + expired + "/pay", null, null, 409);
        String held = buyer.create(skuId, 1, "payment-held", 200).path("data").path("id").asText();
        // 票档停售阻止新下单，但不撤销已预占的未到期待支付订单。
        jdbc.update("UPDATE ticket_sku SET status='OFF_SALE' WHERE id=?", skuId);
        buyer.call("POST", "/orders/" + held + "/pay", null, null, 200);
        invariant(0, 1);
        assertThat(paymentCount()).isEqualTo(1);
    }

    @Test
    void failedPaymentRecordWriteRollsBackStatusInventoryAndRecordAndRetrySucceeds() throws Exception {
        String id = buyer.create(skuId, 2, "payment-rollback", 200).path("data").path("id").asText();
        doThrow(new DataIntegrityViolationException("验收注入支付流水写入失败")).when(paymentMapper).insert(any(PaymentRecord.class));
        try {
            buyer.call("POST", "/orders/" + id + "/pay", null, null, 409);
            invariant(2, 0);
            assertThat(orderStatus(id)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT paid_at IS NULL FROM ticket_order WHERE id=?", Boolean.class, Long.parseLong(id))).isTrue();
            assertThat(paymentCount()).isZero();
            assertThat(used(buyer)).isEqualTo(2);
        } finally { reset(paymentMapper); }
        buyer.call("POST", "/orders/" + id + "/pay", null, null, 200);
        invariant(0, 2);
        assertThat(paymentCount()).isEqualTo(1);
    }

    @Test
    void expiryUsesDatabaseDeadlineAndReleasesInventoryAndQuotaOnce() throws Exception {
        String id = buyer.create(skuId, 3, "expiry", 200).path("data").path("id").asText();
        assertThat(orderService.expire(buyer.id, Long.parseLong(id))).isFalse();
        makeDue(id);
        assertThat(orderService.expire(buyer.id, Long.parseLong(id))).isTrue();
        assertThat(orderService.expire(buyer.id, Long.parseLong(id))).isFalse();
        assertThat(orderStatus(id)).isEqualTo("EXPIRED");
        invariant(0, 0);
        assertThat(used(buyer)).isZero();
        JsonNode closed = buyer.call("GET", "/orders/" + id, null, null, 200).path("data");
        assertThat(closed.path("canPay").asBoolean()).isFalse();
        assertThat(closed.path("canCancel").asBoolean()).isFalse();
        buyer.create(skuId, 4, "quota-recovered", 200);
        invariant(4, 0);
    }

    @Test
    void concurrentExpiryTransactionsReleaseOnlyOnce() throws Exception {
        String id = buyer.create(skuId, 2, "expiry-parallel", 200).path("data").path("id").asText();
        makeDue(id);
        List<Boolean> results = parallel(6, index -> orderService.expire(buyer.id, Long.parseLong(id)));
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        assertThat(orderStatus(id)).isEqualTo("EXPIRED");
        invariant(0, 0);
        assertThat(used(buyer)).isZero();
        assertThat(paymentCount()).isZero();
        String next = buyer.create(skuId, 2, "parallel-jobs", 200).path("data").path("id").asText();
        makeDue(next);
        parallel(4, index -> { new OrderExpiryJob(orderMapper, orderService, new OrderProperties(4, 15, new OrderProperties.Expiry(100))).closeExpiredOrders(); return true; });
        assertThat(orderStatus(next)).isEqualTo("EXPIRED");
        invariant(0, 0);
        assertThat(used(buyer)).isZero();
    }

    @Test
    void paymentAndExpiryRaceBeforeDeadlineOnlyPaymentChangesState() throws Exception {
        String id = buyer.create(skuId, 2, "pay-expire-before", 200).path("data").path("id").asText();
        var results = parallel(2, index -> index == 0
                ? buyer.call("POST", "/orders/" + id + "/pay", null, null, 200).path("data").path("status").asText()
                : Boolean.toString(orderService.expire(buyer.id, Long.parseLong(id))));
        assertThat(results).containsExactly("SUCCEEDED", "false");
        assertThat(orderStatus(id)).isEqualTo("PAID");
        invariant(0, 2);
        assertThat(paymentCount()).isEqualTo(1);
    }

    @Test
    void lockWaitCrossingDatabaseDeadlineCannotExtendPaymentWindow() throws Exception {
        String id = buyer.create(skuId, 2, "pay-expire-wait", 200).path("data").path("id").asText();
        // 持有第三个真实连接的用户锁，让支付和过期事务都等待；数据库截止后才释放。
        try (var blocker = Objects.requireNonNull(jdbc.getDataSource()).getConnection();
                var pool = Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);
            try {
                try (var statement = blocker.prepareStatement("SELECT id FROM user_account WHERE id=? FOR UPDATE")) {
                    statement.setLong(1, buyer.id);
                    try (var result = statement.executeQuery()) { assertThat(result.next()).isTrue(); }
                }
                try (var statement = blocker.prepareStatement("UPDATE ticket_order SET created_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 MINUTE),expires_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 400000 MICROSECOND) WHERE id=?")) {
                    statement.setLong(1, Long.parseLong(id)); statement.executeUpdate();
                }
                Future<JsonNode> payment = pool.submit(() -> buyer.call("POST", "/orders/" + id + "/pay", null, null, 409));
                Future<Boolean> expiry = pool.submit(() -> orderService.expire(buyer.id, Long.parseLong(id)));
                // 等待数据库自己越过截止，不靠Java时钟决定支付资格。
                try (var statement = blocker.prepareStatement("SELECT SLEEP(0.6)")) { statement.execute(); }
                blocker.commit();
                payment.get(20, TimeUnit.SECONDS);
                assertThat(expiry.get(20, TimeUnit.SECONDS)).isTrue();
            } finally { blocker.rollback(); }
        }
        assertThat(orderStatus(id)).isEqualTo("EXPIRED");
        invariant(0, 0);
        assertThat(paymentCount()).isZero();
    }

    @Test
    void paymentAndCancellationRaceHaveExactlyOneFinalEffect() throws Exception {
        String id = buyer.create(skuId, 2, "pay-cancel", 200).path("data").path("id").asText();
        var results = parallel(2, index -> buyer.call("POST", "/orders/" + id + (index == 0 ? "/pay" : "/cancel"), null, null, -1).path("httpStatus").asInt());
        assertThat(results).containsExactlyInAnyOrder(200, 409);
        assertTerminalInventory(id, 2);
    }

    @Test
    void paymentCancellationAndExpiryRacePreserveOneTerminalState() throws Exception {
        String id = buyer.create(skuId, 2, "pay-cancel-expire", 200).path("data").path("id").asText();
        makeDue(id);
        parallel(3, index -> {
            if (index == 2) return Boolean.toString(orderService.expire(buyer.id, Long.parseLong(id)));
            return buyer.call("POST", "/orders/" + id + (index == 0 ? "/pay" : "/cancel"), null, null, -1).path("httpStatus").asText();
        });
        assertThat(orderStatus(id)).isIn("CANCELLED", "EXPIRED");
        assertTerminalInventory(id, 2);
        assertThat(paymentCount()).isZero();
    }

    @Test
    void expiryCleansDisabledAccountsAndUnavailableParents() throws Exception {
        String id = buyer.create(skuId, 2, "disabled-expiry", 200).path("data").path("id").asText();
        makeDue(id);
        jdbc.update("UPDATE user_account SET status='DISABLED' WHERE id=?", buyer.id);
        jdbc.update("UPDATE activity SET status='OFFLINE' WHERE id=?", activityId);
        jdbc.update("UPDATE activity_session SET status='CANCELLED' WHERE id=?", sessionId);
        new OrderExpiryJob(orderMapper, orderService, new OrderProperties(4, 15, new OrderProperties.Expiry(100))).closeExpiredOrders();
        assertThat(orderStatus(id)).isEqualTo("EXPIRED");
        invariant(0, 0);
        assertThat(used(buyer)).isZero();
    }

    @Test
    void boundedScanUsesExpiryIndexAndFailureDoesNotStopOthersOrPreventRetry() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < 3; index++) ids.add(buyer.create(skuId, index == 1 ? 2 : 1, "scan-" + index, 200).path("data").path("id").asText());
        for (String id : ids) makeDue(id);
        assertThat(orderMapper.expiredCandidates(2, null, 0)).hasSize(2);
        assertThat(jdbc.queryForMap("EXPLAIN SELECT id,user_id FROM ticket_order FORCE INDEX(idx_order_expiry) WHERE status='PENDING' AND expires_at<=UTC_TIMESTAMP(6) ORDER BY expires_at,id LIMIT 2").get("key")).isEqualTo("idx_order_expiry");
        OrderExpiryJob job = new OrderExpiryJob(orderMapper, orderService, new OrderProperties(4, 15, new OrderProperties.Expiry(2)));
        doThrow(new DataIntegrityViolationException("验收注入单笔释放失败")).when(inventory).release(skuId, 1);
        try { job.closeExpiredOrders(); }
        finally { reset(orderMapper, inventory); }
        assertThat(orderStatus(ids.get(0))).isEqualTo("PENDING");
        assertThat(orderStatus(ids.get(1))).isEqualTo("EXPIRED");
        assertThat(orderStatus(ids.get(2))).isEqualTo("PENDING");
        invariant(2, 0);
        assertThat(used(buyer)).isEqualTo(2);
        job.closeExpiredOrders();
        assertThat(orderStatus(ids.get(0))).isEqualTo("PENDING");
        assertThat(orderStatus(ids.get(2))).isEqualTo("EXPIRED");
        job.closeExpiredOrders();
        for (String id : ids) assertThat(orderStatus(id)).isEqualTo("EXPIRED");
        invariant(0, 0);
        assertThat(used(buyer)).isZero();
        // 批量为1且第一笔持续失败时，也必须让后面的正常订单先被处理。
        String blocked = buyer.create(skuId, 1, "scan-one-failure", 200).path("data").path("id").asText();
        String following = buyer.create(skuId, 2, "scan-one-following", 200).path("data").path("id").asText();
        makeDue(blocked);
        makeDue(following);
        OrderExpiryJob single = new OrderExpiryJob(orderMapper, orderService, new OrderProperties(4, 15, new OrderProperties.Expiry(1)));
        doThrow(new DataIntegrityViolationException("验收注入持续释放失败")).when(inventory).release(skuId, 1);
        try { single.closeExpiredOrders(); single.closeExpiredOrders(); }
        finally { reset(orderMapper, inventory); }
        assertThat(orderStatus(blocked)).isEqualTo("PENDING");
        assertThat(orderStatus(following)).isEqualTo("EXPIRED");
        invariant(1, 0);
        single.closeExpiredOrders();
        assertThat(orderStatus(blocked)).isEqualTo("EXPIRED");
        invariant(0, 0);
    }

    private void makeDue(String id) {
        jdbc.update("UPDATE ticket_order SET created_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 20 MINUTE),expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?", Long.parseLong(id));
    }
    private String orderStatus(String id) { return jdbc.queryForObject("SELECT status FROM ticket_order WHERE id=?", String.class, Long.parseLong(id)); }
    private int paymentCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM payment_record p JOIN ticket_order o ON p.order_id=o.id WHERE o.session_id=?", Integer.class, sessionId); }
    private void assertTerminalInventory(String id, int quantity) {
        String state = orderStatus(id);
        assertThat(state).isIn("PAID", "CANCELLED", "EXPIRED");
        boolean paid = "PAID".equals(state);
        invariant(0, paid ? quantity : 0);
        assertThat(paymentCount()).isEqualTo(paid ? 1 : 0);
        assertThat(used(buyer)).isEqualTo(paid ? quantity : 0);
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
            var accepted = call("POST", "/orders", Map.of("skuId", Long.toString(sku), "quantity", quantity), key, -1);
            int http = accepted.path("httpStatus").asInt();
            if (http != 202 && !(http == 200 && accepted.path("data").has("requestId"))) {
                if (expected >= 0) assertThat(http).isEqualTo(expected);
                ((com.fasterxml.jackson.databind.node.ObjectNode) accepted).put("creationOutcome", "REJECTED");
                return accepted;
            }
            assertThat(http).isIn(200, 202); // 这里只证明可靠受理，绝不当作订单完成。
            var result = PurchaseTestSupport.resolve(purchaseWorker, purchaseRequests, jdbc, accepted.path("data").path("requestId").asLong());
            if (expected == 200) assertThat(result.status()).isEqualTo("SUCCEEDED");
            if (expected == 409 || expected == 400) assertThat(result.status()).isEqualTo("FAILED");
            var response = result.orderId() != null
                    ? call("GET", "/orders/" + result.orderId(), null, null, 200)
                    : call("GET", "/purchase-requests/" + result.id(), null, null, 200);
            ((com.fasterxml.jackson.databind.node.ObjectNode) response).put("creationOutcome", result.status());
            return response;
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
