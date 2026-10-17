package com.ticketing.checkin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.auth.dto.RegisterRequest;
import com.ticketing.auth.service.AccountService;
import com.ticketing.checkin.entity.ElectronicTicket;
import com.ticketing.checkin.mapper.ElectronicTicketMapper;
import com.ticketing.checkin.security.TicketCredentialSigner;
import com.ticketing.checkin.service.ElectronicTicketService;
import com.ticketing.checkin.service.TicketBackfillJob;
import com.ticketing.order.mapper.OrderMapper;
import com.ticketing.order.service.OrderService;
import com.ticketing.waitlist.service.WaitlistDispatcher;
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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/** 全部认证通过真实HTTP，核销竞争使用不同连接上的独立事务，并检查最终数据库事实。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.servlet.session.cookie.secure=false", "ticketing.admin-bootstrap.enabled=false"})
class ElectronicTicketIT {
    private static final String FIRST_KEY = randomKey();
    private static final String SECOND_KEY = randomKey();
    private static final String KEY_CONFIG = "{\"1\":\"" + FIRST_KEY + "\",\"2\":\"" + SECOND_KEY + "\"}";
    @DynamicPropertySource
    static void signingKeys(DynamicPropertyRegistry properties) {
        properties.add("ticketing.checkin.credential-keys", () -> KEY_CONFIG);
        properties.add("ticketing.checkin.current-key-version", () -> 2);
    }
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountService accounts;
    @Autowired private OrderService orders;
    @Autowired private OrderMapper orderMapper;
    @Autowired private ElectronicTicketService service;
    @Autowired private TicketCredentialSigner signer;
    @Autowired private WaitlistDispatcher dispatcher;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private org.mybatis.spring.SqlSessionTemplate sqlSession;
    @MockitoSpyBean private ElectronicTicketMapper tickets;
    private final List<Long> userIds = new ArrayList<>();
    private long activityId;
    private long sessionId;
    private long skuId;
    private Client buyer;
    private Client other;
    private Client admin;
    private Client secondAdmin;

    @BeforeEach
    void fixture() throws Exception {
        buyer = client(false); other = client(false); admin = client(true); secondAdmin = client(true);
        String title = "电子票验收" + UUID.randomUUID();
        jdbc.update("INSERT INTO activity(title,category,city,venue,status,published_at) VALUES(?,'CONCERT','上海','电子票测试场馆','PUBLISHED',UTC_TIMESTAMP(6))", title);
        activityId = jdbc.queryForObject("SELECT id FROM activity WHERE title=?", Long.class, title);
        jdbc.update("INSERT INTO activity_session(activity_id,title,starts_at,ends_at,sale_starts_at) VALUES(?,'核销测试',DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 30 MINUTE),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 2 HOUR),DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 DAY))", activityId);
        sessionId = jdbc.queryForObject("SELECT id FROM activity_session WHERE activity_id=?", Long.class, activityId);
        jdbc.update("INSERT INTO ticket_sku(session_id,name,price_cents,total_stock,sale_starts_at,sale_ends_at,status) VALUES(?,'电子票测试票',12500,20,DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 HOUR),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 20 MINUTE),'ON_SALE')", sessionId);
        skuId = jdbc.queryForObject("SELECT id FROM ticket_sku WHERE session_id=?", Long.class, sessionId);
    }
    @AfterEach
    void cleanup() {
        reset(tickets);
        if (sessionId != 0) {
            jdbc.update("DELETE e FROM outbox_event e JOIN ticket_sku s ON e.aggregate_id=s.id WHERE e.aggregate_type='TICKET_SKU' AND s.session_id=?", sessionId);
            jdbc.update("DELETE w FROM waitlist_entry w JOIN ticket_sku s ON w.sku_id=s.id WHERE s.session_id=?", sessionId);
            jdbc.update("DELETE t FROM electronic_ticket t JOIN ticket_order o ON t.order_id=o.id WHERE o.session_id=?", sessionId);
            jdbc.update("DELETE p FROM payment_record p JOIN ticket_order o ON p.order_id=o.id WHERE o.session_id=?", sessionId);
            jdbc.update("DELETE i FROM ticket_order_item i JOIN ticket_order o ON i.order_id=o.id WHERE o.session_id=?", sessionId);
            jdbc.update("DELETE FROM ticket_order WHERE session_id=?", sessionId);
            jdbc.update("DELETE FROM ticket_sku WHERE session_id=?", sessionId);
            jdbc.update("DELETE FROM activity_session WHERE id=?", sessionId);
        }
        if (activityId != 0) jdbc.update("DELETE FROM activity WHERE id=?", activityId);
        for (long id : userIds) jdbc.update("DELETE FROM user_account WHERE id=?", id);
    }

    @Test
    void successfulPaymentIssuesOneIndependentTicketPerPurchasedUnit() throws Exception {
        long order = paid(3);
        var rows = issued(order);
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(ElectronicTicket::getSequenceNo).containsExactly(1, 2, 3);
        assertThat(rows).extracting(ElectronicTicket::getTicketNo).doesNotHaveDuplicates();
        var credentials = new ArrayList<String>();
        for (var ticket : rows) {
            assertThat(ticket.getOrderId()).isEqualTo(order);
            assertThat(ticket.getSkuId()).isEqualTo(skuId);
            assertThat(ticket.getOwnerId()).isEqualTo(buyer.id);
            assertThat(ticket.getStatus()).isEqualTo("UNUSED");
            assertThat(ticket.getCredentialKeyVersion()).isEqualTo(2);
            assertThat(ticket.getUsedAt()).isNull(); assertThat(ticket.getCheckedBy()).isNull();
            String credential = credential(ticket);
            assertThat(signer.verify(credential).digest()).isEqualTo(ticket.getCredentialDigest());
            credentials.add(credential);
        }
        assertThat(credentials).doesNotHaveDuplicates();
        stock(0, 3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_record WHERE order_id=?", Integer.class, order)).isEqualTo(1);
    }
    @Test
    void repeatedPaymentAndConcurrentIssuanceNeverCreateAdditionalTickets() throws Exception {
        long order = paid(2);
        var before = issued(order).stream().map(ElectronicTicket::getTicketNo).toList();
        parallel(6, index -> index % 2 == 0 ? service.backfill(order) : buyer.call("POST", "/orders/" + order + "/pay", null, 200).path("httpStatus").asInt());
        assertThat(issued(order)).extracting(ElectronicTicket::getTicketNo).containsExactlyElementsOf(before);
        stock(0, 2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_record WHERE order_id=?", Integer.class, order)).isEqualTo(1);
    }
    @Test
    void pendingCancelledAndExpiredOrdersNeverIssueTickets() throws Exception {
        long pending = create(1);
        long cancelled = create(1); buyer.call("POST", "/orders/" + cancelled + "/cancel", null, 200);
        long expired = create(1);
        jdbc.update("UPDATE ticket_order SET created_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 20 MINUTE),expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?", expired);
        assertThat(orders.expire(buyer.id, expired)).isTrue();
        for (long order : List.of(pending, cancelled, expired)) {
            assertThatThrownBy(() -> service.backfill(order)).isInstanceOf(com.ticketing.common.api.BusinessException.class);
            assertThat(issued(order)).isEmpty();
        }
        stock(1, 0);
    }
    @Test
    void waitlistPaymentIssuesTicketsAndCompletesTheSameTransaction() throws Exception {
        long order = allocated(2);
        buyer.call("POST", "/orders/" + order + "/pay", null, 200);
        assertThat(issued(order)).hasSize(2); stock(0, 2);
        assertThat(jdbc.queryForObject("SELECT status FROM waitlist_entry WHERE reserved_order_id=?", String.class, order)).isEqualTo("COMPLETED");
    }
    @Test
    void failingSecondTicketRollsBackPaymentInventoryRecordAndAllTickets() throws Exception {
        long order = create(2);
        failSecondInsert();
        try { buyer.call("POST", "/orders/" + order + "/pay", null, 409); }
        finally { reset(tickets); }
        assertThat(orderStatus(order)).isEqualTo("PENDING"); stock(2, 0);
        assertThat(issued(order)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_record WHERE order_id=?", Integer.class, order)).isZero();
        buyer.call("POST", "/orders/" + order + "/pay", null, 200);
        assertThat(issued(order)).hasSize(2); stock(0, 2);
    }
    @Test
    void issuanceFailureAlsoRollsBackWaitlistCompletion() throws Exception {
        long order = allocated(2);
        failSecondInsert();
        try { buyer.call("POST", "/orders/" + order + "/pay", null, 409); }
        finally { reset(tickets); }
        assertThat(orderStatus(order)).isEqualTo("PENDING"); stock(2, 0);
        assertThat(issued(order)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM waitlist_entry WHERE reserved_order_id=?", String.class, order)).isEqualTo("ALLOCATED");
    }
    @Test
    void legacyPaidOrderBackfillsWithoutChangingFinancialOrInventoryFacts() throws Exception {
        long order = historical(3);
        assertThat(issued(order)).isEmpty();
        parallel(5, index -> service.backfill(order));
        assertThat(issued(order)).hasSize(3);
        assertThat(service.backfill(order)).isZero();
        assertThat(orderStatus(order)).isEqualTo("PAID"); stock(0, 3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_record WHERE order_id=?", Integer.class, order)).isEqualTo(1);
    }
    @Test
    void partialLegacyIssuanceFillsMissingSequencesAndPreservesExistingTicket() throws Exception {
        long order = historical(3);
        ElectronicTicket existing = legacyTicket(order, 2, 1);
        assertThat(service.backfill(order)).isEqualTo(2);
        assertThat(issued(order)).extracting(ElectronicTicket::getSequenceNo).containsExactly(1, 2, 3);
        ElectronicTicket preserved = tickets.selectById(existing.getId());
        assertThat(preserved.getTicketNo()).isEqualTo(existing.getTicketNo());
        assertThat(preserved.getCredentialDigest()).isEqualTo(existing.getCredentialDigest());
        assertThat(service.backfill(order)).isZero(); stock(0, 3);
    }
    @Test
    void anomalousLegacyQuantityIsRejectedWithoutDeletingOrAddingTickets() throws Exception {
        long order = historical(2);
        ElectronicTicket unexpected = legacyTicket(order, 3, 1);
        assertThatThrownBy(() -> service.backfill(order)).isInstanceOf(com.ticketing.common.api.BusinessException.class);
        assertThat(issued(order)).extracting(ElectronicTicket::getId).containsExactly(unexpected.getId()); stock(0, 2);
    }
    @Test
    void boundedHistoricalScannerContinuesPastAnInconsistentOrder() throws Exception {
        long broken = historical(1); legacyTicket(broken, 2, 1);
        long normal = historical(1);
        var job = new TicketBackfillJob(tickets, service, 1);
        // 只选择本用例范围，避免在共用开发库中处理其他用户的真实历史订单。
        ReflectionTestUtils.setField(job, "cursor", broken - 1);
        job.scan(); assertThat(issued(normal)).isEmpty();
        job.scan(); assertThat(issued(normal)).hasSize(1);
        assertThat(issued(broken)).hasSize(1);
        assertThat(tickets.paidCandidates(broken - 1, 1)).hasSize(1);
    }

    @Test
    void onlyOwnerCanReadTicketDetailsAndCredentialsIncludingForAdministrators() throws Exception {
        long order = paid(2); var ticket = issued(order).getFirst();
        String path = "/tickets/" + ticket.getId();
        assertThat(buyer.call("GET", "/tickets?orderId=" + order, null, 200).path("data").path("total").asInt()).isEqualTo(2);
        assertThat(other.call("GET", "/tickets", null, 200).path("data").path("total").asInt()).isZero();
        for (Client client : List.of(other, admin)) {
            client.call("GET", path, null, 404); client.call("GET", path + "/credential", null, 404);
            client.call("GET", "/tickets?orderId=" + order, null, 404);
        }
        String details = buyer.call("GET", path, null, 200).path("data").toString();
        assertThat(details).contains("电子票测试场馆").doesNotContain("credential", "digest", "keyVersion", FIRST_KEY, SECOND_KEY);
        assertThat(buyer.call("GET", "/tickets?status=UNUSED", null, 200).path("data").path("total").asInt()).isEqualTo(2);
        buyer.call("GET", "/tickets?status=INVALID", null, 400);
        buyer.call("GET", "/tickets?page=0", null, 400);
        buyer.call("GET", "/tickets?size=51", null, 400);
    }
    @Test
    void credentialResponseIsNotCachedAndDatabaseOnlyKeepsDigest() throws Exception {
        var ticket = issued(paid(1)).getFirst();
        String value = credential(ticket);
        assertThat(buyer.lastResponse.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(buyer.lastResponse.headers().firstValue("Pragma").orElse("")).isEqualTo("no-cache");
        var row = jdbc.queryForMap("SELECT * FROM electronic_ticket WHERE id=?", ticket.getId());
        assertThat(row.keySet()).doesNotContain("credential");
        assertThat((byte[]) row.get("credential_digest")).isEqualTo(signer.digest(value));
        assertThat(credential(ticket)).isEqualTo(value);
    }
    @Test
    void normalCheckinRecordsActualStaffAndDoesNotChangePaymentOrSoldStock() throws Exception {
        long order = paid(2); var rows = issued(order);
        JsonNode result = admin.check(credential(rows.getFirst()), 200).path("data");
        assertThat(result.path("status").asText()).isEqualTo("USED");
        assertThat(result.path("checkedBy").asText()).isEqualTo(Long.toString(admin.id));
        assertThat(result.path("usedAt").asText()).endsWith("Z");
        assertThat(result.toString()).doesNotContain("credentialDigest", "credentialKeyVersion", FIRST_KEY, SECOND_KEY);
        assertThat(tickets.selectById(rows.get(1).getId()).getStatus()).isEqualTo("UNUSED");
        assertThat(orderStatus(order)).isEqualTo("PAID"); stock(0, 2);
    }
    @Test
    void repeatedScanKeepsOriginalStaffAndTimestampAndHidesUsedQr() throws Exception {
        var ticket = issued(paid(1)).getFirst(); String value = credential(ticket);
        admin.check(value, 200);
        ElectronicTicket first = tickets.selectById(ticket.getId());
        assertThat(secondAdmin.check(value, 409).path("message").asText()).contains("已核销");
        ElectronicTicket after = tickets.selectById(ticket.getId());
        assertThat(after.getUsedAt()).isEqualTo(first.getUsedAt());
        assertThat(after.getCheckedBy()).isEqualTo(admin.id);
        buyer.call("GET", "/tickets/" + ticket.getId() + "/credential", null, 409);
        assertThat(buyer.call("GET", "/tickets?status=USED", null, 200).path("data").path("total").asInt()).isEqualTo(1);
        stock(0, 1);
    }
    @Test
    void multipleStaffOnIndependentHttpTransactionsHaveExactlyOneCheckinWinner() throws Exception {
        var ticket = issued(paid(1)).getFirst(); String value = credential(ticket);
        var results = parallel(8, index -> (index % 2 == 0 ? admin : secondAdmin).check(value, -1).path("httpStatus").asInt());
        assertThat(results.stream().filter(code -> code == 200).count()).isEqualTo(1);
        assertThat(results.stream().filter(code -> code == 409).count()).isEqualTo(7);
        ElectronicTicket finalTicket = tickets.selectById(ticket.getId());
        assertThat(finalTicket.getStatus()).isEqualTo("USED"); assertThat(finalTicket.getUsedAt()).isNotNull();
        assertThat(finalTicket.getCheckedBy()).isIn(admin.id, secondAdmin.id); stock(0, 1);
    }
    @Test
    void invalidSignatureTicketNumberAndUnknownVersionDoNotUseTickets() throws Exception {
        var ticket = issued(paid(1)).getFirst(); String value = credential(ticket);
        String[] parts = value.split("\\.");
        for (String invalid : List.of("10001", value.replace("TKT1.2.", "TKT1.3."),
                "TKT1.2." + signer.newTicketNo() + "." + parts[3],
                value.substring(0, value.lastIndexOf('.') + 1) + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]),
                signer.credential(signer.newTicketNo(), 2))) admin.check(invalid, 400);
        assertThat(tickets.selectById(ticket.getId()).getStatus()).isEqualTo("UNUSED"); stock(0, 1);
    }
    @Test
    void validHmacStillRequiresTheStoredDigestAndVersion() throws Exception {
        var ticket = issued(paid(1)).getFirst(); String value = credential(ticket);
        jdbc.update("UPDATE electronic_ticket SET credential_digest=? WHERE id=?", new byte[32], ticket.getId());
        admin.check(value, 400); buyer.call("GET", "/tickets/" + ticket.getId() + "/credential", null, 400);
        jdbc.update("UPDATE electronic_ticket SET credential_digest=?,credential_key_version=1 WHERE id=?", signer.digest(value), ticket.getId());
        admin.check(value, 400);
        assertThat(tickets.selectById(ticket.getId()).getUsedAt()).isNull(); stock(0, 1);
    }
    @Test
    void oldVersionTicketRemainsUsableAfterRotationAndNewTicketsUseCurrentVersion() throws Exception {
        long oldOrder = historical(1); ElectronicTicket old = legacyTicket(oldOrder, 1, 1);
        String oldCredential = credential(old);
        assertThat(oldCredential).startsWith("TKT1.1.");
        var fresh = issued(paid(1)).getFirst(); assertThat(credential(fresh)).startsWith("TKT1.2.");
        admin.check(oldCredential, 200);
        admin.check(credential(fresh), 200);
        assertThat(tickets.selectById(old.getId()).getStatus()).isEqualTo("USED"); stock(0, 2);
    }
    @Test
    void futureEndedAndCancelledSessionsAreRejectedByDatabaseWindowConditions() throws Exception {
        var ticket = issued(paid(1)).getFirst(); String value = credential(ticket);
        jdbc.update("UPDATE activity_session SET starts_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 2 HOUR),ends_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 3 HOUR) WHERE id=?", sessionId);
        admin.check(value, 409);
        jdbc.update("UPDATE activity_session SET starts_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 2 HOUR),ends_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 HOUR) WHERE id=?", sessionId);
        admin.check(value, 409);
        jdbc.update("UPDATE activity_session SET status='CANCELLED' WHERE id=?", sessionId);
        assertThat(admin.check(value, 409).path("message").asText()).contains("场次");
        ElectronicTicket current = tickets.selectById(ticket.getId());
        assertThat(current.getStatus()).isEqualTo("UNUSED"); assertThat(current.getUsedAt()).isNull(); assertThat(current.getCheckedBy()).isNull(); stock(0, 1);
    }
    @Test
    void voidTicketsAndTicketsOfUnpaidOrdersCannotBeCheckedIn() throws Exception {
        long order = paid(2); var rows = issued(order); String first = credential(rows.getFirst()); String second = credential(rows.get(1));
        jdbc.update("UPDATE electronic_ticket SET status='VOID' WHERE id=?", rows.getFirst().getId());
        assertThat(admin.check(first, 409).path("message").asText()).contains("作废");
        buyer.call("GET", "/tickets/" + rows.getFirst().getId() + "/credential", null, 409);
        jdbc.update("UPDATE ticket_order SET status='PENDING',paid_at=NULL WHERE id=?", order);
        assertThat(admin.check(second, 409).path("message").asText()).contains("未支付");
        assertThat(tickets.selectById(rows.get(1).getId()).getStatus()).isEqualTo("UNUSED"); stock(0, 2);
    }
    @Test
    void checkinRequiresRealAdministratorAndValidCsrfAndRejectsForgedStaffFields() throws Exception {
        var ticket = issued(paid(1)).getFirst(); String value = credential(ticket);
        Client anonymous = new Client(); anonymous.csrf();
        anonymous.call("GET", "/tickets", null, 401); anonymous.check(value, 401);
        buyer.check(value, 403); other.check(value, 403);
        String token = admin.token;
        admin.token = ""; admin.check(value, 403); admin.token = "wrong"; admin.check(value, 403); admin.token = token;
        admin.call("POST", "/admin/checkins", Map.of("credential", value, "checkedBy", secondAdmin.id), 400);
        admin.call("POST", "/admin/checkins", Map.of("credential", ""), 400);
        admin.call("POST", "/admin/checkins", Map.of("credential", "x".repeat(257)), 400);
        assertThat(tickets.selectById(ticket.getId()).getStatus()).isEqualTo("UNUSED");
        admin.check(value, 200); stock(0, 1);
    }
    @Test
    void withdrawnAdminAndDisabledOwnerLosePreviouslyEstablishedSessionAccess() throws Exception {
        var ticket = issued(paid(1)).getFirst(); String value = credential(ticket);
        jdbc.update("UPDATE user_account SET role='USER' WHERE id=?", admin.id); admin.check(value, 403);
        jdbc.update("UPDATE user_account SET status='DISABLED' WHERE id=?", secondAdmin.id); secondAdmin.check(value, 401);
        jdbc.update("UPDATE user_account SET status='DISABLED' WHERE id=?", buyer.id); buyer.call("GET", "/tickets/" + ticket.getId() + "/credential", null, 401);
        assertThat(tickets.selectById(ticket.getId()).getStatus()).isEqualTo("UNUSED"); stock(0, 1);
    }
    @Test
    void checkinWriteFailureCannotLeavePartialUsageMetadata() throws Exception {
        var ticket = issued(paid(1)).getFirst(); String value = credential(ticket);
        doAnswer(invocation -> { sqlSession.getMapper(ElectronicTicketMapper.class).checkIn(ticket.getId(), admin.id, 60); throw new DataIntegrityViolationException("验收注入核销后失败"); })
                .when(tickets).checkIn(ticket.getId(), admin.id, 60);
        try { admin.check(value, 409); } finally { reset(tickets); }
        ElectronicTicket unchanged = tickets.selectById(ticket.getId());
        assertThat(unchanged.getStatus()).isEqualTo("UNUSED"); assertThat(unchanged.getUsedAt()).isNull(); assertThat(unchanged.getCheckedBy()).isNull();
        admin.check(value, 200); stock(0, 1);
    }
    @Test
    void backfillAndCheckinShareTheLockOrderAndPreserveAlreadyUsedTicket() throws Exception {
        long order = historical(3); ElectronicTicket existing = legacyTicket(order, 1, 1); String value = credential(existing);
        parallel(4, index -> index == 0 ? admin.check(value, 200).path("httpStatus").asInt() : service.backfill(order));
        assertThat(issued(order)).hasSize(3);
        assertThat(tickets.selectById(existing.getId()).getStatus()).isEqualTo("USED");
        assertThat(tickets.selectById(existing.getId()).getCheckedBy()).isEqualTo(admin.id);
        assertThat(service.backfill(order)).isZero(); stock(0, 3);
    }

    private long create(int quantity) throws Exception { return buyer.create(skuId, quantity).path("id").asLong(); }
    private long paid(int quantity) throws Exception { long order = create(quantity); buyer.call("POST", "/orders/" + order + "/pay", null, 200); return order; }
    private long allocated(int quantity) throws Exception {
        jdbc.update("UPDATE ticket_sku SET total_stock=0 WHERE id=?", skuId);
        JsonNode entry = buyer.call("POST", "/waitlist", Map.of("skuId", Long.toString(skuId), "quantity", quantity), 200).path("data");
        jdbc.update("UPDATE ticket_sku SET total_stock=? WHERE id=?", quantity, skuId);
        dispatcher.dispatch(skuId);
        return jdbc.queryForObject("SELECT reserved_order_id FROM waitlist_entry WHERE id=?", Long.class, entry.path("id").asLong());
    }
    /** 模拟第七阶段上线前已存在的PAID/流水事实，保留真实创建、锁序和库存转移；不删除任何已有票券。 */
    private long historical(int quantity) throws Exception {
        long order = create(quantity);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            orders.lockPurchase(buyer.id, skuId, true);
            jdbc.queryForMap("SELECT id FROM ticket_order WHERE id=? FOR UPDATE", order);
            assertThat(orderMapper.markPaid(order, buyer.id)).isEqualTo(1);
            assertThat(orderMapper.sell(skuId, quantity)).isEqualTo(1);
            jdbc.update("INSERT INTO payment_record(order_id,payment_no,amount_cents,status,paid_at) SELECT id,?,total_amount_cents,'SUCCEEDED',paid_at FROM ticket_order WHERE id=?", UUID.randomUUID().toString(), order);
        });
        return order;
    }
    private ElectronicTicket legacyTicket(long order, int sequence, int version) {
        var source = tickets.source(order);
        ElectronicTicket ticket = new ElectronicTicket();
        ticket.setTicketNo(signer.newTicketNo()); ticket.setCredentialKeyVersion(version);
        ticket.setCredentialDigest(signer.digest(signer.credential(ticket.getTicketNo(), version)));
        ticket.setOrderId(order); ticket.setOrderItemId(source.orderItemId()); ticket.setSkuId(source.skuId());
        ticket.setOwnerId(source.ownerId()); ticket.setSequenceNo(sequence); ticket.setStatus("UNUSED"); tickets.insert(ticket);
        return ticket;
    }
    private void failSecondInsert() {
        AtomicInteger count = new AtomicInteger();
        doAnswer(invocation -> {
            if (count.incrementAndGet() == 2) throw new DataIntegrityViolationException("验收注入第二张出票失败");
            return sqlSession.getMapper(ElectronicTicketMapper.class).insert((ElectronicTicket) invocation.getArgument(0));
        }).when(tickets).insert(any(ElectronicTicket.class));
    }
    private List<ElectronicTicket> issued(long order) {
        return tickets.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ElectronicTicket>()
                .eq(ElectronicTicket::getOrderId, order).orderByAsc(ElectronicTicket::getSequenceNo));
    }
    private String credential(ElectronicTicket ticket) throws Exception {
        return buyer.call("GET", "/tickets/" + ticket.getId() + "/credential", null, 200).path("data").path("credential").asText();
    }
    private String orderStatus(long order) { return jdbc.queryForObject("SELECT status FROM ticket_order WHERE id=?", String.class, order); }
    private void stock(int locked, int sold) {
        var row = jdbc.queryForMap("SELECT total_stock,locked_stock,sold_stock FROM ticket_sku WHERE id=?", skuId);
        assertThat(((Number) row.get("locked_stock")).intValue()).isEqualTo(locked);
        assertThat(((Number) row.get("sold_stock")).intValue()).isEqualTo(sold);
        assertThat(locked).isGreaterThanOrEqualTo(0); assertThat(sold).isGreaterThanOrEqualTo(0);
        assertThat(locked + sold).isLessThanOrEqualTo(((Number) row.get("total_stock")).intValue());
    }
    private Client client(boolean administrator) throws Exception {
        Client client = new Client(); String username = "et_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24); String password = UUID.randomUUID().toString();
        client.csrf();
        if (administrator) accounts.initializeAdmin(new RegisterRequest(username, password));
        else client.call("POST", "/auth/register", Map.of("username", username, "password", password), 200);
        client.id = jdbc.queryForObject("SELECT id FROM user_account WHERE username=?", Long.class, username); userIds.add(client.id);
        client.raw("POST", "/auth/login", "username=" + username + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8), "application/x-www-form-urlencoded", null, 200);
        client.csrf(); return client;
    }
    private <T> List<T> parallel(int count, CheckedFunction<T> action) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(count)) {
            List<Future<T>> futures = new ArrayList<>();
            for (int index = 0; index < count; index++) { int value = index; futures.add(pool.submit(() -> { start.await(); return action.apply(value); })); }
            start.countDown(); List<T> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(25, TimeUnit.SECONDS)); return results;
        }
    }
    @FunctionalInterface private interface CheckedFunction<T> { T apply(int index) throws Exception; }
    private static String randomKey() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getEncoder().encodeToString(bytes); }
    private class Client {
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        long id; String token = "";
        HttpResponse<String> lastResponse;
        void csrf() throws Exception { token = call("GET", "/auth/csrf", null, 200).path("data").path("token").asText(); }
        JsonNode check(String value, int expected) throws Exception { return call("POST", "/admin/checkins", Map.of("credential", value), expected); }
        JsonNode create(long sku, int quantity) throws Exception { return raw("POST", "/orders", json.writeValueAsString(Map.of("skuId", Long.toString(sku), "quantity", quantity)), "application/json", UUID.randomUUID().toString(), 200).path("data"); }
        JsonNode call(String method, String path, Object body, int expected) throws Exception { return raw(method, path, body == null ? "" : json.writeValueAsString(body), "application/json", null, expected); }
        JsonNode raw(String method, String path, String body, String type, String key, int expected) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path)).timeout(Duration.ofSeconds(20)).header("Content-Type", type);
            if (!token.isEmpty()) request.header("X-CSRF-TOKEN", token);
            if (key != null) request.header("Idempotency-Key", key);
            var response = http.send(request.method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            lastResponse = response;
            if (expected >= 0) assertThat(response.statusCode()).as("%s %s %s", method, path, response.body()).isEqualTo(expected);
            var parsed = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(response.body()); parsed.put("httpStatus", response.statusCode()); return parsed;
        }
    }
}
