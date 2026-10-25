package com.ticketing;

import com.ticketing.dto.RegisterRequest;
import com.ticketing.security.AccountPrincipal;
import com.ticketing.service.AccountService;
import com.ticketing.service.AccountUserDetailsService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TicketManagementIT {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountService accounts;
    @Autowired private AccountUserDetailsService identities;
    private AccountPrincipal administrator;
    private String activityId;
    private String sessionId;

    @BeforeEach
    void parents() throws Exception {
        String name = "ticket_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        accounts.initializeAdmin(new RegisterRequest(name, UUID.randomUUID().toString()));
        administrator = identities.loadUserByUsername(name);
        administrator.eraseCredentials();
        var activity = mutate(post("/api/v1/admin/activities"), Map.of("title", "票档测试" + UUID.randomUUID(),
                "category", "CONCERT", "city", "上海", "venue", "测试场馆", "summary", "测试简介", "description", "测试详情"), 200);
        activityId = activity.path("id").asText();
        mutate(post("/api/v1/admin/activities/" + activityId + "/publish"), Map.of("version", 0), 200);
        sessionId = mutate(post("/api/v1/admin/activities/" + activityId + "/sessions"), sessionPayload(), 200).path("id").asText();
    }

    @Test
    void createsDraftAndPublishesSortedPublicTicketsWithExactCents() throws Exception {
        JsonNode standard = create("普通票");
        assertThat(standard.path("id").isTextual()).isTrue();
        assertThat(standard.path("priceCents").asText()).isEqualTo("29900");
        assertThat(standard.path("availableStock").asInt()).isEqualTo(10);
        publicGet().andExpect(jsonPath("$.data.length()").value(0));
        mutate(post(ticket(standard) + "/publish"), Map.of("version", 0), 200);
        var low = payload("优惠票", 0);
        low.put("priceCents", "9007199254740993");
        JsonNode exact = mutate(post(base()), low, 200);
        assertThat(exact.path("priceCents").asText()).isEqualTo("9007199254740993");
        mutate(post(ticket(exact) + "/publish"), Map.of("version", 0), 200);
        publicGet().andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].name").value("普通票"))
                .andExpect(jsonPath("$.data[0].saleStatus").value("SELLING"))
                .andExpect(jsonPath("$.data[0].serverTime").isString());
        mvc.perform(get(base()).with(user(administrator))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].lockedStock").value(0)).andExpect(jsonPath("$.data[0].soldStock").value(0));
    }

    @Test
    void sameSessionNameIsUniqueOnCreateAndEdit() throws Exception {
        create("普通票");
        mutate(post(base()), payload(" 普通票 ", 0), 409);
        JsonNode second = create("其他票");
        mutate(put(ticket(second)), payload("普通票", 0), 409);
        String otherSession = mutate(post("/api/v1/admin/activities/" + activityId + "/sessions"), sessionPayload(), 200).path("id").asText();
        mutate(post("/api/v1/admin/sessions/" + otherSession + "/tickets"), payload("普通票", 0), 200);
    }

    @Test
    void rejectsInvalidMoneyStockLimitsReadonlyFieldsAndMissingVersion() throws Exception {
        List<Map.Entry<String, Object>> invalid = List.of(
                Map.entry("priceCents", "-1"), Map.entry("priceCents", "1.5"), Map.entry("priceCents", "9223372036854775808"),
                Map.entry("totalStock", -1), Map.entry("totalStock", 1.5), Map.entry("totalStock", 2147483648L),
                Map.entry("maxQuantityPerOrder", 0), Map.entry("maxQuantityPerOrder", 1.5));
        for (var entry : invalid) {
            var body = payload("非法参数", 0);
            body.put(entry.getKey(), entry.getValue());
            mutate(post(base()), body, 400);
        }
        JsonNode sku = create("只读库存");
        for (String field : List.of("lockedStock", "soldStock", "availableStock", "sessionId", "status")) {
            var body = payload("只读库存", 0);
            body.put(field, 1);
            mutate(put(ticket(sku)), body, 400);
        }
        var missing = payload("只读库存", 0);
        missing.remove("version");
        mutate(put(ticket(sku)), missing, 400);
        assertThat(jdbc.queryForObject("SELECT locked_stock + sold_stock FROM ticket_sku WHERE id = ?", Integer.class, sku.path("id").asLong())).isZero();
    }

    @Test
    void stockUpdatesPreserveLockedAndSoldAndProtectEnabledRules() throws Exception {
        JsonNode sku = create("库存票");
        // 仅构造未来库存事实夹具，不提供任何修改锁定或已售库存的业务接口。
        jdbc.update("UPDATE ticket_sku SET locked_stock = 2, sold_stock = 3 WHERE id = ?", sku.path("id").asLong());
        var body = payload("库存票", 0);
        body.put("totalStock", 4);
        mutate(put(ticket(sku)), body, 409);
        body.put("totalStock", 5);
        JsonNode changed = mutate(put(ticket(sku)), body, 200);
        assertThat(changed.path("availableStock").asInt()).isZero();
        assertThat(changed.path("lockedStock").asInt()).isEqualTo(2);
        assertThat(changed.path("soldStock").asInt()).isEqualTo(3);
        mutate(post(ticket(sku) + "/publish"), Map.of("version", 1), 200);
        body.put("version", 2);
        body.put("totalStock", 7);
        mutate(put(ticket(sku)), body, 200);
        body.put("version", 3);
        body.put("priceCents", "30000");
        mutate(put(ticket(sku)), body, 409);
        mutate(post(ticket(sku) + "/offline"), Map.of("version", 3), 200);
        body.put("version", 4);
        mutate(put(ticket(sku)), body, 200);
    }

    @Test
    void validatesWindowsAndPreventsSessionChangesInvalidatingTickets() throws Exception {
        for (var change : List.of(Map.entry("saleStartsAt", "2024-01-01T00:00:00Z"),
                Map.entry("saleEndsAt", "2036-01-01T00:00:00Z"), Map.entry("saleEndsAt", "2024-01-01T00:00:00Z"),
                Map.entry("saleStartsAt", "2025-02-01T10:00:00"))) {
            var body = payload("时间错误", 0);
            body.put(change.getKey(), change.getValue());
            mutate(post(base()), body, 400);
        }
        create("关联时间");
        var laterBaseline = sessionPayload();
        laterBaseline.put("version", 0);
        laterBaseline.put("saleStartsAt", "2025-03-01T00:00:00Z");
        mutate(put("/api/v1/admin/sessions/" + sessionId), laterBaseline, 409);
        var earlyStart = sessionPayload();
        earlyStart.put("version", 0);
        earlyStart.put("startsAt", "2034-01-01T00:00:00Z");
        mutate(put("/api/v1/admin/sessions/" + sessionId), earlyStart, 409);
    }

    @Test
    void managementTransitionsAndPublicOwnershipAreEnforced() throws Exception {
        JsonNode sku = create("状态票");
        mutate(post(ticket(sku) + "/offline"), Map.of("version", 0), 409);
        mutate(post(ticket(sku) + "/publish"), Map.of("version", 0), 200);
        mutate(post(ticket(sku) + "/publish"), Map.of("version", 1), 409);
        mutate(post(ticket(sku) + "/offline"), Map.of("version", 0), 409);
        mutate(post(ticket(sku) + "/offline"), Map.of("version", 1), 200);
        publicGet().andExpect(jsonPath("$.data.length()").value(0));
        mutate(post(ticket(sku) + "/publish"), Map.of("version", 2), 200);
        mvc.perform(get("/api/v1/activities/999999999/sessions/" + sessionId + "/tickets")).andExpect(status().isNotFound());
        String wrongOwner = mutate(post("/api/v1/admin/activities"), Map.of("title", "其他活动", "category", "CONCERT",
                "city", "北京", "venue", "测试场馆", "summary", "测试简介", "description", "测试详情"), 200).path("id").asText();
        mutate(post("/api/v1/admin/activities/" + wrongOwner + "/publish"), Map.of("version", 0), 200);
        mvc.perform(get("/api/v1/activities/" + wrongOwner + "/sessions/" + sessionId + "/tickets")).andExpect(status().isNotFound());
        String other = mutate(post("/api/v1/admin/activities/" + activityId + "/sessions"), sessionPayload(), 200).path("id").asText();
        mvc.perform(get("/api/v1/activities/" + activityId + "/sessions/" + other + "/tickets"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        mutate(post("/api/v1/admin/activities/" + activityId + "/offline"), Map.of("version", 1), 200);
        publicGetStatus(404);
        mvc.perform(get(base()).with(user(administrator))).andExpect(jsonPath("$.data[0].saleStatus").value("UNAVAILABLE"));
        mutate(post("/api/v1/admin/activities/" + activityId + "/publish"), Map.of("version", 2), 200);
        mutate(post("/api/v1/admin/sessions/" + sessionId + "/cancel"), Map.of("version", 0), 200);
        publicGet().andExpect(jsonPath("$.data[0].saleStatus").value("UNAVAILABLE"));
        mutate(put(ticket(sku)), payload("状态票", 3), 409);
    }

    @Test
    void anonymousOrdinaryRoleAndMissingCsrfCannotMutateTickets() throws Exception {
        String name = "user_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        accounts.register(new RegisterRequest(name, UUID.randomUUID().toString()));
        AccountPrincipal ordinary = identities.loadUserByUsername(name);
        ordinary.eraseCredentials();
        String body = json.writeValueAsString(payload("权限票", 0));
        mvc.perform(post(base()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(base()).with(user(ordinary)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(base()).with(user(administrator))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(get(base())).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_sku WHERE session_id = ?", Integer.class, Long.parseLong(sessionId))).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentEditorsWithSameVersionHaveOnlyOneWinner() throws Exception {
        try {
            JsonNode sku = create("并发票");
            CountDownLatch start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
                for (int index = 0; index < 2; index++) {
                    final String name = "并发修改" + index;
                    futures.add(pool.submit(() -> {
                        start.await();
                        return mvc.perform(put(ticket(sku)).with(user(administrator)).with(csrf())
                                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload(name, 0))))
                                .andReturn().getResponse().getStatus();
                    }));
                }
                start.countDown();
                assertThat(List.of(futures.get(0).get(15, TimeUnit.SECONDS), futures.get(1).get(15, TimeUnit.SECONDS)))
                        .containsExactlyInAnyOrder(200, 409);
            }
            assertThat(jdbc.queryForObject("SELECT version FROM ticket_sku WHERE id = ?", Integer.class, sku.path("id").asLong())).isEqualTo(1);
        } finally {
            jdbc.update("DELETE e FROM outbox_event e JOIN ticket_sku s ON e.aggregate_id=s.id WHERE e.aggregate_type='TICKET_SKU' AND s.session_id=?",Long.parseLong(sessionId));
            jdbc.update("DELETE FROM ticket_sku WHERE session_id = ?", Long.parseLong(sessionId));
            jdbc.update("DELETE FROM activity_session WHERE id = ?", Long.parseLong(sessionId));
            jdbc.update("DELETE FROM activity WHERE id = ?", Long.parseLong(activityId));
            jdbc.update("DELETE FROM user_account WHERE id = ? AND id NOT IN (SELECT operator_id FROM admin_audit_log)", administrator.getId());
        }
    }

    private JsonNode create(String name) throws Exception { return mutate(post(base()), payload(name, 0), 200); }
    private String base() { return "/api/v1/admin/sessions/" + sessionId + "/tickets"; }
    private String ticket(JsonNode sku) { return "/api/v1/admin/tickets/" + sku.path("id").asText(); }
    private org.springframework.test.web.servlet.ResultActions publicGet() throws Exception {
        return mvc.perform(get("/api/v1/activities/" + activityId + "/sessions/" + sessionId + "/tickets")).andExpect(status().isOk());
    }
    private void publicGetStatus(int code) throws Exception {
        mvc.perform(get("/api/v1/activities/" + activityId + "/sessions/" + sessionId + "/tickets")).andExpect(status().is(code));
    }
    private Map<String, Object> sessionPayload() {
        return new HashMap<>(Map.of("title", "测试场次", "startsAt", "2035-06-01T10:00:00Z", "endsAt", "2035-06-01T12:00:00Z", "saleStartsAt", "2025-01-01T00:00:00Z"));
    }
    private Map<String, Object> payload(String name, int version) {
        return new HashMap<>(Map.of("name", name, "priceCents", "29900", "totalStock", 10,
                "saleStartsAt", "2025-02-01T00:00:00Z", "saleEndsAt", "2035-05-01T00:00:00Z", "maxQuantityPerOrder", 4, "version", version));
    }
    private JsonNode mutate(MockHttpServletRequestBuilder request, Object body, int expected) throws Exception {
        String response = mvc.perform(request.with(user(administrator)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(response).path("data");
    }
}
