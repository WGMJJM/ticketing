package com.ticketing;

import com.ticketing.dto.RegisterRequest;
import com.ticketing.mapper.AuditMapper;
import com.ticketing.mapper.PurchaseRequestMapper;
import com.ticketing.service.AccountService;
import com.ticketing.service.PurchaseOrderWorker;
import com.ticketing.service.StaffService;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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
import java.util.concurrent.TimeoutException;

/** 真实HTTP、Cookie、CSRF及独立MySQL事务；追加审计及其账号/授权历史保留在独立验收库。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.servlet.session.cookie.secure=false",
            "ticketing.admin-bootstrap.enabled=false"
        })
class StaffAuditIT {
    private static final String KEYS = keys();

    @DynamicPropertySource
    static void signingKeys(DynamicPropertyRegistry properties) {
        properties.add("ticketing.checkin.credential-keys", () -> KEYS);
        properties.add("ticketing.checkin.current-key-version", () -> 1);
    }

    private static String keys() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return "{\"1\":\"" + Base64.getEncoder().encodeToString(key) + "\"}";
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountService accounts;
    @Autowired com.ticketing.service.PurchaseOrderWorker purchaseWorker;
    @Autowired com.ticketing.mapper.PurchaseRequestMapper purchaseRequests;
    @Autowired StaffService staffService;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean AuditMapper audit;
    Client admin, otherAdmin, buyer, staff, secondStaff;
    final List<Long> users = new ArrayList<>();
    long activityId, sessionId, otherSessionId, skuId, otherSkuId;

    @BeforeEach
    void fixture() throws Exception {
        admin = account(true);
        otherAdmin = account(true);
        buyer = account(false);
        staff = account(false);
        secondStaff = account(false);
        String title = "工作人员审计验收" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO activity(title,category,city,venue,status,published_at) VALUES(?,'CONCERT','上海','授权验收场馆','PUBLISHED',UTC_TIMESTAMP(6))",
                title);
        activityId =
                jdbc.queryForObject("SELECT id FROM activity WHERE title=?", Long.class, title);
        sessionId = session("授权场次");
        otherSessionId = session("未授权场次");
        skuId = sku(sessionId);
        otherSkuId = sku(otherSessionId);
    }

    long session(String name) {
        jdbc.update(
                "INSERT INTO activity_session(activity_id,title,starts_at,ends_at,sale_starts_at) VALUES(?,?,TIMESTAMPADD(MINUTE,30,UTC_TIMESTAMP(6)),TIMESTAMPADD(HOUR,2,UTC_TIMESTAMP(6)),TIMESTAMPADD(DAY,-1,UTC_TIMESTAMP(6)))",
                activityId,
                name);
        return jdbc.queryForObject(
                "SELECT id FROM activity_session WHERE activity_id=? AND title=?",
                Long.class,
                activityId,
                name);
    }

    long sku(long session) {
        jdbc.update(
                "INSERT INTO ticket_sku(session_id,name,price_cents,total_stock,sale_starts_at,sale_ends_at,status) VALUES(?,'工作人员验收票',12800,20,TIMESTAMPADD(HOUR,-1,UTC_TIMESTAMP(6)),TIMESTAMPADD(MINUTE,20,UTC_TIMESTAMP(6)),'ON_SALE')",
                session);
        return jdbc.queryForObject(
                "SELECT id FROM ticket_sku WHERE session_id=?", Long.class, session);
    }

    @AfterEach
    void preserveAuditHistory() {
        reset(audit);
        for (long id : users) {
            jdbc.update("UPDATE user_account SET status='DISABLED' WHERE id=?", id);
        }
    }

    @Test
    void registrationCannotChooseStaffRoleAndOnlyAdministratorsManagePermissionsOrAudit()
            throws Exception {
        Client anonymous = new Client();
        anonymous.csrf();
        String username =
                "forged_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        anonymous.call(
                "POST",
                "/auth/register",
                Map.of(
                        "username",
                        username,
                        "password",
                        UUID.randomUUID().toString(),
                        "role",
                        "CHECKIN_STAFF"),
                400);
        assertThat(count("SELECT COUNT(*) FROM user_account WHERE username=?", username)).isZero();
        for (String path : List.of("/admin/staff", "/admin/audit-logs", "/checkins/sessions")) {
            anonymous.call("GET", path, null, 401);
            buyer.call("GET", path, null, 403);
        }
        buyer.call("POST", "/admin/staff/" + staff.id + "/promote", null, 403);
        admin.call("POST", "/admin/staff/" + admin.id + "/demote", null, 409);
        admin.call("POST", "/admin/staff/" + staff.id + "/promote", Map.of("role", "ADMIN"), 200);
        assertThat(role(staff.id)).isEqualTo("CHECKIN_STAFF");
        staff.call("GET", "/admin/audit-logs", null, 403);
        staff.call("POST", "/admin/staff/" + buyer.id + "/promote", null, 403);
    }

    @Test
    void staffChecksInOnlyAssignedSessionsAndNeverReceivesAdministratorPrivileges()
            throws Exception {
        authorize(staff, sessionId);
        String allowed = credential(paid(skuId));
        String forbidden = credential(paid(otherSkuId));
        var available = staff.call("GET", "/checkins/sessions", null, 200).path("data");
        assertThat(available.path("total").asInt()).isEqualTo(1);
        assertThat(available.path("items").get(0).path("sessionId").asText())
                .isEqualTo(Long.toString(sessionId));
        staff.call("POST", "/checkins", Map.of("credential", forbidden), 403);
        var result =
                staff.call("POST", "/checkins", Map.of("credential", allowed), 200).path("data");
        assertThat(result.path("checkedBy").asText()).isEqualTo(Long.toString(staff.id));
        for (String path :
                List.of(
                        "/admin/orders",
                        "/admin/activities",
                        "/admin/sessions/" + sessionId + "/tickets")) {
            staff.call("GET", path, null, 403);
        }
        staff.call("POST", "/admin/checkins", Map.of("credential", forbidden), 403);
        staff.call("POST", "/admin/orders/1/refund", Map.of("reason", "CUSTOMER_REQUEST"), 403);
        staff.call("PUT", "/admin/tickets/" + skuId, Map.of("totalStock", 100), 403);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM electronic_ticket WHERE sku_id=? AND status='USED'",
                                skuId))
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM electronic_ticket WHERE sku_id=? AND status='UNUSED'",
                                otherSkuId))
                .isEqualTo(1);
        stock(0, 1);
    }

    @Test
    void existingAdministratorEndpointKeepsGlobalCheckinBehavior() throws Exception {
        String credential = credential(paid(otherSkuId));
        admin.call("POST", "/admin/checkins", Map.of("credential", credential), 200);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM electronic_ticket WHERE sku_id=? AND checked_by=?",
                                otherSkuId,
                                admin.id))
                .isEqualTo(1);
    }

    @Test
    void revokingSessionInvalidatesOldStaffSessionWithoutChangingOtherGrants() throws Exception {
        authorize(staff, sessionId);
        authorize(staff, otherSessionId);
        String credential = credential(paid(skuId));
        String cookie = staff.cookies();
        admin.call("POST", permission(staff, sessionId) + "/revoke", null, 200);
        staff.call("POST", "/checkins", Map.of("credential", credential), 403);
        assertThat(staff.cookies()).isEqualTo(cookie);
        assertThat(
                        staff.call("GET", "/checkins/sessions", null, 200)
                                .path("data")
                                .path("items")
                                .get(0)
                                .path("sessionId")
                                .asText())
                .isEqualTo(Long.toString(otherSessionId));
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM electronic_ticket WHERE sku_id=? AND status='UNUSED'",
                                skuId))
                .isEqualTo(1);
    }

    @Test
    void demotionRefreshesOldSessionAndDoesNotRestoreStaleGrantsOnPromotion() throws Exception {
        authorize(staff, sessionId);
        String credential = credential(paid(skuId));
        admin.call("POST", "/admin/staff/" + staff.id + "/demote", null, 200);
        staff.call("POST", "/checkins", Map.of("credential", credential), 403);
        assertThat(staff.call("GET", "/auth/me", null, 200).path("data").path("role").asText())
                .isEqualTo("USER");
        admin.call("POST", "/admin/staff/" + staff.id + "/promote", null, 200);
        staff.call("POST", "/checkins", Map.of("credential", credential), 403);
        assertThat(
                        staff.call("GET", "/checkins/sessions", null, 200)
                                .path("data")
                                .path("total")
                                .asInt())
                .isZero();
    }

    @Test
    void disablingStaffInvalidatesExistingCookieAndRetainsAuditAndGrantHistory() throws Exception {
        authorize(staff, sessionId);
        String credential = credential(paid(skuId));
        admin.call("POST", "/admin/staff/" + staff.id + "/disable", null, 200);
        staff.call("GET", "/auth/me", null, 401);
        staff.csrf();
        staff.call("POST", "/checkins", Map.of("credential", credential), 401);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM staff_session_permission WHERE staff_id=? AND revoked_at IS NOT NULL",
                                staff.id))
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM admin_audit_log WHERE operator_id=? AND action='STAFF_DISABLE'",
                                admin.id))
                .isEqualTo(1);
    }

    @Test
    void allNewWriteEndpointsRejectMissingOrInvalidCsrf() throws Exception {
        authorize(staff, sessionId);
        String credential = credential(paid(skuId));
        String token = staff.token;
        staff.token = "";
        staff.call("POST", "/checkins", Map.of("credential", credential), 403);
        staff.token = "invalid-token";
        staff.call("POST", "/checkins", Map.of("credential", credential), 403);
        staff.token = token;
        token = admin.token;
        admin.token = "";
        for (String suffix :
                List.of(
                        "/promote",
                        "/demote",
                        "/disable",
                        "/sessions/" + sessionId + "/grant",
                        "/sessions/" + sessionId + "/revoke")) {
            admin.call("POST", "/admin/staff/" + staff.id + suffix, null, 403);
        }
        admin.token = token;
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM electronic_ticket WHERE sku_id=? AND status='UNUSED'",
                                skuId))
                .isEqualTo(1);
    }

    @Test
    void twoAuthorizedStaffHaveExactlyOneDatabaseCheckinWinner() throws Exception {
        authorize(staff, sessionId);
        authorize(secondStaff, sessionId);
        String credential = credential(paid(skuId));
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var first =
                    pool.submit(
                            () -> {
                                start.await();
                                return staff.status(
                                        "POST", "/checkins", Map.of("credential", credential));
                            });
            var second =
                    pool.submit(
                            () -> {
                                start.await();
                                return secondStaff.status(
                                        "POST", "/checkins", Map.of("credential", credential));
                            });
            start.countDown();
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT checked_by FROM electronic_ticket WHERE sku_id=?",
                                Long.class,
                                skuId))
                .isIn(staff.id, secondStaff.id);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM electronic_ticket WHERE sku_id=? AND status='USED'",
                                skuId))
                .isEqualTo(1);
        stock(0, 1);
    }

    @Test
    void inFlightRevocationBlocksOldCookieUntilCommittedThenDeniesCheckin() throws Exception {
        authorize(staff, sessionId);
        String credential = credential(paid(skuId));
        CountDownLatch revoked = new CountDownLatch(1), commit = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var revocation =
                    pool.submit(
                            () ->
                                    transaction()
                                            .executeWithoutResult(
                                                    status -> {
                                                        staffService.revoke(
                                                                admin.id, staff.id, sessionId);
                                                        revoked.countDown();
                                                        try {
                                                            if (!commit.await(10, TimeUnit.SECONDS))
                                                                throw new IllegalStateException(
                                                                        "验收等待超时");
                                                        } catch (InterruptedException exception) {
                                                            Thread.currentThread().interrupt();
                                                            throw new IllegalStateException(
                                                                    exception);
                                                        }
                                                    }));
            assertThat(revoked.await(10, TimeUnit.SECONDS)).isTrue();
            var checkin =
                    pool.submit(
                            () ->
                                    staff.status(
                                            "POST", "/checkins", Map.of("credential", credential)));
            try {
                assertThatThrownBy(() -> checkin.get(300, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                commit.countDown();
            }
            revocation.get(20, TimeUnit.SECONDS);
            assertThat(checkin.get(20, TimeUnit.SECONDS)).isEqualTo(403);
        } finally {
            commit.countDown();
        }
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM electronic_ticket WHERE sku_id=? AND status='UNUSED' AND checked_by IS NULL",
                                skuId))
                .isEqualTo(1);
    }

    @Test
    void checkinAndRevocationRaceHasOnlySerializableAuthorizedOutcomes() throws Exception {
        authorize(staff, sessionId);
        String credential = credential(paid(skuId));
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var first =
                    pool.submit(
                            () -> {
                                start.await();
                                return staff.status(
                                        "POST", "/checkins", Map.of("credential", credential));
                            });
            var second =
                    pool.submit(
                            () -> {
                                start.await();
                                return admin.status(
                                        "POST", permission(staff, sessionId) + "/revoke", null);
                            });
            start.countDown();
            int result = first.get(30, TimeUnit.SECONDS);
            assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(result).isIn(200, 403);
            assertThat(
                            count(
                                    "SELECT COUNT(*) FROM electronic_ticket WHERE sku_id=? AND status='USED'",
                                    skuId))
                    .isEqualTo(result == 200 ? 1 : 0);
            staff.call("POST", "/checkins", Map.of("credential", credential), 403);
        }
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM staff_session_permission WHERE staff_id=? AND revoked_at IS NOT NULL",
                                staff.id))
                .isEqualTo(1);
    }

    @Test
    void duplicateGrantIsIdempotentAndConstraintsKeepOnePermissionRecord() throws Exception {
        authorize(staff, sessionId);
        authorize(staff, sessionId);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM staff_session_permission WHERE staff_id=? AND session_id=?",
                                staff.id,
                                sessionId))
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM admin_audit_log WHERE operator_id=? AND action='SESSION_GRANT'",
                                admin.id))
                .isEqualTo(1);
        admin.call("POST", permission(staff, sessionId) + "/revoke", null, 200);
        admin.call("POST", permission(staff, sessionId) + "/grant", null, 200);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM staff_session_permission WHERE staff_id=? AND session_id=?",
                                staff.id,
                                sessionId))
                .isEqualTo(1);
        staff.call("GET", "/checkins/sessions?size=51", null, 400);
        admin.call(
                "POST",
                "/admin/staff/" + buyer.id + "/sessions/" + sessionId + "/grant",
                null,
                409);
        admin.call("POST", "/admin/staff/" + staff.id + "/sessions/0/grant", null, 400);
    }

    @Test
    void auditIsAppendOnlyAndRecordsExactCommittedOldAndNewBusinessValues() throws Exception {
        stockUpdate(admin, 21, 0, 200);
        var row =
                jdbc.queryForMap(
                        "SELECT before_values,after_values FROM admin_audit_log WHERE operator_id=? AND action='STOCK_ADJUST'",
                        admin.id);
        assertThat(json.readTree(row.get("before_values").toString()).path("totalStock").asInt())
                .isEqualTo(20);
        assertThat(json.readTree(row.get("after_values").toString()).path("totalStock").asInt())
                .isEqualTo(21);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE admin_audit_log SET summary='改写' WHERE operator_id=?",
                                        admin.id))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "DELETE FROM admin_audit_log WHERE operator_id=?",
                                        admin.id))
                .isInstanceOf(DataAccessException.class);
        jdbc.update("UPDATE user_account SET status='DISABLED' WHERE id=?", admin.id);
        assertThat(
                        otherAdmin
                                .call("GET", "/admin/audit-logs?operatorId=" + admin.id, null, 200)
                                .path("data")
                                .path("total")
                                .asInt())
                .isEqualTo(1);
    }

    @Test
    void auditFailureRollsBackBusinessAndBusinessFailureNeverWritesSuccessAudit() throws Exception {
        stockUpdate(admin, 21, 99, 409);
        assertThat(count("SELECT COUNT(*) FROM admin_audit_log WHERE operator_id=?", admin.id))
                .isZero();
        doThrow(new DataIntegrityViolationException("验收注入审计失败"))
                .when(audit)
                .append(
                        anyLong(),
                        anyString(),
                        anyString(),
                        anyLong(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString());
        stockUpdate(admin, 21, 0, 409);
        reset(audit);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT total_stock FROM ticket_sku WHERE id=?",
                                Integer.class,
                                skuId))
                .isEqualTo(20);
        assertThat(count("SELECT COUNT(*) FROM admin_audit_log WHERE operator_id=?", admin.id))
                .isZero();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND aggregate_type='TICKET_SKU'",
                                skuId))
                .isZero();
    }

    @Test
    void concurrentStockEditsAuditOnlyTheSuccessfulVersionAndPreserveValueChain() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var first =
                    pool.submit(
                            () -> {
                                start.await();
                                return stockUpdate(admin, 21, 0, -1);
                            });
            var second =
                    pool.submit(
                            () -> {
                                start.await();
                                return stockUpdate(otherAdmin, 22, 0, -1);
                            });
            start.countDown();
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        var logs =
                jdbc.queryForList(
                        "SELECT before_values,after_values FROM admin_audit_log WHERE resource_type='TICKET_SKU' AND resource_id=?",
                        skuId);
        assertThat(logs).hasSize(1);
        assertThat(
                        json.readTree(logs.get(0).get("before_values").toString())
                                .path("totalStock")
                                .asInt())
                .isEqualTo(20);
        int current =
                jdbc.queryForObject(
                        "SELECT total_stock FROM ticket_sku WHERE id=?", Integer.class, skuId);
        assertThat(
                        json.readTree(logs.get(0).get("after_values").toString())
                                .path("totalStock")
                                .asInt())
                .isEqualTo(current);
        stockUpdate(admin, 23, 1, 200);
        var newest =
                jdbc.queryForObject(
                        "SELECT before_values FROM admin_audit_log WHERE resource_type='TICKET_SKU' AND resource_id=? ORDER BY id DESC LIMIT 1",
                        String.class,
                        skuId);
        assertThat(json.readTree(newest).path("totalStock").asInt()).isEqualTo(current);
    }

    @Test
    void auditQueriesValidateFiltersAndNeverExposeAuthenticationOrCredentialMaterial()
            throws Exception {
        authorize(staff, sessionId);
        stockUpdate(admin, 21, 0, 200);
        var response =
                admin.call(
                        "GET",
                        "/admin/audit-logs?operatorId="
                                + admin.id
                                + "&action=STOCK_ADJUST&resourceType=TICKET_SKU&resourceId="
                                + skuId,
                        null,
                        200);
        var data = response.path("data");
        assertThat(data.path("total").asInt()).isEqualTo(1);
        assertThat(data.path("items").get(0).path("resourceId").isTextual()).isTrue();
        assertThat(data.path("items").get(0).path("requestId").asText()).matches("[0-9a-f-]{36}");
        assertThat(response.toString())
                .doesNotContain(
                        admin.password,
                        staff.password,
                        admin.token,
                        staff.token,
                        "passwordHash",
                        "credential",
                        "JSESSIONID",
                        "TKT1.");
        for (String query :
                List.of(
                        "size=51",
                        "page=0",
                        "operatorId=-1",
                        "resourceType=USER_ACCOUNT",
                        "action=UNKNOWN",
                        "from=2030-01-02T00:00:00Z&to=2030-01-01T00:00:00Z")) {
            admin.call("GET", "/admin/audit-logs?" + query, null, 400);
        }
    }

    @Test
    void administratorRefundAndManualAftercareAreAuditedWithActualResult() throws Exception {
        long first = paid(skuId);
        admin.call(
                "POST",
                "/admin/orders/" + first + "/refund",
                Map.of("reason", "CUSTOMER_REQUEST"),
                200);
        long second = paid(skuId);
        admin.call("POST", "/admin/checkins", Map.of("credential", credential(second)), 200);
        var result =
                admin.call(
                        "POST",
                        "/admin/orders/" + second + "/refund",
                        Map.of("reason", "CUSTOMER_REQUEST"),
                        200);
        assertThat(result.path("data").path("status").asText()).isEqualTo("MANUAL_REQUIRED");
        var logs =
                jdbc.queryForList(
                        "SELECT after_values FROM admin_audit_log WHERE operator_id=? AND action='ORDER_REFUND' ORDER BY id",
                        admin.id);
        assertThat(logs).hasSize(2);
        assertThat(
                        json.readTree(logs.get(0).get("after_values").toString())
                                .path("result")
                                .asText())
                .isEqualTo("SUCCEEDED");
        assertThat(
                        json.readTree(logs.get(1).get("after_values").toString())
                                .path("result")
                                .asText())
                .isEqualTo("MANUAL_REQUIRED");
    }

    @Test
    void managementLifecycleAuditsEverySuccessfulMutationAndFullContentChanges() throws Exception {
        var activity =
                admin.call(
                                "POST",
                                "/admin/activities",
                                Map.of(
                                        "title",
                                        "审计生命周期",
                                        "category",
                                        "CONCERT",
                                        "city",
                                        "上海",
                                        "venue",
                                        "验收场馆",
                                        "summary",
                                        "原简介",
                                        "description",
                                        "原活动详情"),
                                200)
                        .path("data");
        String activityPath = "/admin/activities/" + activity.path("id").asText();
        admin.call(
                "PUT",
                activityPath,
                Map.of(
                        "title",
                        "修改后的审计活动",
                        "category",
                        "CONCERT",
                        "city",
                        "上海",
                        "venue",
                        "验收场馆",
                        "summary",
                        "新简介",
                        "description",
                        "新活动详情",
                        "version",
                        0),
                200);
        String start = "2035-06-01T10:00:00Z",
                end = "2035-06-01T12:00:00Z",
                sale = "2025-01-01T00:00:00Z";
        var session =
                admin.call(
                                "POST",
                                activityPath + "/sessions",
                                Map.of(
                                        "title",
                                        "审计场次",
                                        "startsAt",
                                        start,
                                        "endsAt",
                                        end,
                                        "saleStartsAt",
                                        sale),
                                200)
                        .path("data");
        var ticketBody =
                new java.util.HashMap<String, Object>(
                        Map.of(
                                "name",
                                "审计票档",
                                "priceCents",
                                "100",
                                "totalStock",
                                10,
                                "saleStartsAt",
                                sale,
                                "saleEndsAt",
                                "2035-05-31T00:00:00Z",
                                "maxQuantityPerOrder",
                                4));
        var ticket =
                admin.call(
                                "POST",
                                "/admin/sessions/" + session.path("id").asText() + "/tickets",
                                ticketBody,
                                200)
                        .path("data");
        String ticketPath = "/admin/tickets/" + ticket.path("id").asText();
        ticketBody.put("priceCents", "120");
        ticketBody.put("version", 0);
        admin.call("PUT", ticketPath, ticketBody, 200);
        admin.call("POST", ticketPath + "/publish", Map.of("version", 1), 200);
        admin.call("POST", ticketPath + "/offline", Map.of("version", 2), 200);
        String sessionPath = "/admin/sessions/" + session.path("id").asText();
        admin.call(
                "PUT",
                sessionPath,
                Map.of(
                        "title",
                        "修改后的审计场次",
                        "startsAt",
                        start,
                        "endsAt",
                        end,
                        "saleStartsAt",
                        sale,
                        "version",
                        0),
                200);
        admin.call("POST", sessionPath + "/cancel", Map.of("version", 1), 200);
        admin.call("POST", activityPath + "/publish", Map.of("version", 1), 200);
        admin.call("POST", activityPath + "/offline", Map.of("version", 2), 200);
        var actions =
                jdbc.queryForList(
                        "SELECT action FROM admin_audit_log WHERE operator_id=? ORDER BY id",
                        String.class,
                        admin.id);
        assertThat(actions)
                .containsExactly(
                        "ACTIVITY_CREATE",
                        "ACTIVITY_UPDATE",
                        "SESSION_CREATE",
                        "TICKET_CREATE",
                        "TICKET_UPDATE",
                        "TICKET_PUBLISH",
                        "TICKET_OFFLINE",
                        "SESSION_UPDATE",
                        "SESSION_CANCEL",
                        "ACTIVITY_PUBLISH",
                        "ACTIVITY_OFFLINE");
        var content =
                jdbc.queryForMap(
                        "SELECT before_values,after_values FROM admin_audit_log WHERE operator_id=? AND action='ACTIVITY_UPDATE'",
                        admin.id);
        assertThat(
                        json.readTree(content.get("before_values").toString())
                                .path("description")
                                .asText())
                .isEqualTo("原活动详情");
        assertThat(
                        json.readTree(content.get("after_values").toString())
                                .path("description")
                                .asText())
                .isEqualTo("新活动详情");
    }

    private TransactionTemplate transaction() {
        var tx = new TransactionTemplate(transactions);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx;
    }

    private void authorize(Client user, long session) throws Exception {
        admin.call("POST", "/admin/staff/" + user.id + "/promote", null, 200);
        admin.call("POST", permission(user, session) + "/grant", null, 200);
    }

    private String permission(Client user, long session) {
        return "/admin/staff/" + user.id + "/sessions/" + session;
    }

    private String role(long id) {
        return jdbc.queryForObject("SELECT role FROM user_account WHERE id=?", String.class, id);
    }

    private long count(String sql, Object... parameters) {
        return jdbc.queryForObject(sql, Long.class, parameters);
    }

    private void stock(int locked, int sold) {
        var row =
                jdbc.queryForMap(
                        "SELECT total_stock,locked_stock,sold_stock FROM ticket_sku WHERE id=?",
                        skuId);
        assertThat(row.get("locked_stock")).isEqualTo(locked);
        assertThat(row.get("sold_stock")).isEqualTo(sold);
        assertThat(locked + sold).isLessThanOrEqualTo((Integer) row.get("total_stock"));
    }

    private int stockUpdate(Client client, int total, int version, int expected) throws Exception {
        var row =
                jdbc.queryForMap(
                        "SELECT name,sale_starts_at,sale_ends_at FROM ticket_sku WHERE id=?",
                        skuId);
        return client.send(
                        "PUT",
                        "/admin/tickets/" + skuId,
                        json.writeValueAsString(
                                Map.of(
                                        "name",
                                        row.get("name"),
                                        "priceCents",
                                        "12800",
                                        "totalStock",
                                        total,
                                        "saleStartsAt",
                                        ((java.time.LocalDateTime) row.get("sale_starts_at"))
                                                .atOffset(java.time.ZoneOffset.UTC)
                                                .toString(),
                                        "saleEndsAt",
                                        ((java.time.LocalDateTime) row.get("sale_ends_at"))
                                                .atOffset(java.time.ZoneOffset.UTC)
                                                .toString(),
                                        "maxQuantityPerOrder",
                                        4,
                                        "version",
                                        version)),
                        "application/json",
                        null,
                        expected)
                .statusCode();
    }

    private long paid(long sku) throws Exception {
        var response =
                buyer.send(
                        "POST",
                        "/orders",
                        json.writeValueAsString(Map.of("skuId", Long.toString(sku), "quantity", 1)),
                        "application/json",
                        UUID.randomUUID().toString(),
                        202);
        long requestId = json.readTree(response.body()).path("data").path("requestId").asLong();
        long id = Long.parseLong(PurchaseTestSupport.order(purchaseWorker,purchaseRequests,jdbc,requestId));
        buyer.call("POST", "/orders/" + id + "/pay", null, 200);
        return id;
    }

    private String credential(long orderId) throws Exception {
        long id =
                jdbc.queryForObject(
                        "SELECT MIN(id) FROM electronic_ticket WHERE order_id=?",
                        Long.class,
                        orderId);
        return buyer.call("GET", "/tickets/" + id + "/credential", null, 200)
                .path("data")
                .path("credential")
                .asText();
    }

    private Client account(boolean administrator) throws Exception {
        var client = new Client();
        client.username =
                "staff_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        client.password = UUID.randomUUID().toString();
        client.csrf();
        if (administrator)
            accounts.initializeAdmin(new RegisterRequest(client.username, client.password));
        else
            client.call(
                    "POST",
                    "/auth/register",
                    Map.of("username", client.username, "password", client.password),
                    200);
        client.id =
                jdbc.queryForObject(
                        "SELECT id FROM user_account WHERE username=?",
                        Long.class,
                        client.username);
        users.add(client.id);
        client.send(
                "POST",
                "/auth/login",
                "username="
                        + client.username
                        + "&password="
                        + URLEncoder.encode(client.password, StandardCharsets.UTF_8),
                "application/x-www-form-urlencoded",
                null,
                200);
        client.csrf();
        return client;
    }

    private class Client {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();
        long id;
        String username, password, token = "";

        String cookies() {
            return cookies.getCookieStore().getCookies().toString();
        }

        void csrf() throws Exception {
            token = call("GET", "/auth/csrf", null, 200).path("data").path("token").asText();
        }

        int status(String method, String path, Object body) throws Exception {
            return send(
                            method,
                            path,
                            body == null ? "" : json.writeValueAsString(body),
                            "application/json",
                            null,
                            -1)
                    .statusCode();
        }

        JsonNode call(String method, String path, Object body, int expected) throws Exception {
            return json.readTree(
                    send(
                                    method,
                                    path,
                                    body == null ? "" : json.writeValueAsString(body),
                                    "application/json",
                                    null,
                                    expected)
                            .body());
        }

        HttpResponse<String> send(
                String method,
                String path,
                String body,
                String contentType,
                String key,
                int expected)
                throws Exception {
            var request =
                    HttpRequest.newBuilder(
                                    URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                            .timeout(Duration.ofSeconds(25))
                            .header("Content-Type", contentType);
            if (!token.isEmpty()) request.header("X-CSRF-TOKEN", token);
            if (key != null) request.header("Idempotency-Key", key);
            var response =
                    http.send(
                            request.method(method, HttpRequest.BodyPublishers.ofString(body))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            if (expected >= 0)
                assertThat(response.statusCode()).as("%s %s", method, path).isEqualTo(expected);
            return response;
        }
    }
}
