package com.ticketing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ActivityManagementIT {
    private static final String TEST_PASSWORD = UUID.randomUUID().toString();
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @DynamicPropertySource
    static void administrator(DynamicPropertyRegistry properties) {
        properties.add("ticketing.dev-admin.enabled", () -> true);
        properties.add("ticketing.dev-admin.username", () -> "integration-admin");
        properties.add("ticketing.dev-admin.password", () -> TEST_PASSWORD);
    }

    @Test
    void activityLifecycleRejectsHiddenAndIllegalTransitions() throws Exception {
        JsonNode created = create("生命周期", "上海", "CONCERT");
        String id = created.path("id").asText();
        assertThat(created.path("id").isTextual()).isTrue();
        assertThat(created.path("status").asText()).isEqualTo("DRAFT");
        mvc.perform(get("/api/v1/activities/" + id)).andExpect(status().isNotFound());
        mutate(post(admin(id) + "/offline"), Map.of("version", 0), 409);
        var edited = data(mutate(put(admin(id)), activity("修改后的活动", "上海", "CONCERT", 0), 200));
        assertThat(edited.path("version").asInt()).isEqualTo(1);
        mutate(put(admin(id)), activity("过期编辑", "上海", "CONCERT", 0), 409);
        var published = data(mutate(post(admin(id) + "/publish"), Map.of("version", 1), 200));
        assertThat(published.path("publishedAt").asText()).endsWith("Z");
        mvc.perform(get("/api/v1/activities/" + id)).andExpect(status().isOk());
        mutate(put(admin(id)), activity("不能直接修改已发布活动", "上海", "CONCERT", 2), 409);
        mutate(post(admin(id) + "/publish"), Map.of("version", 2), 409);
        mutate(post(admin(id) + "/offline"), Map.of("version", 2), 200);
        mvc.perform(get("/api/v1/activities/" + id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/activities/" + id + "/sessions")).andExpect(status().isNotFound());
        mutate(post(admin(id) + "/publish"), Map.of("version", 3), 200);
    }

    @Test
    void publicPaginationFiltersAndSortsPublishedRecordsOnly() throws Exception {
        String keyword = "测试筛选" + UUID.randomUUID().toString().substring(0, 8);
        var first = create(keyword + "一", "上海", "CONCERT");
        var second = create(keyword + "二", "上海", "CONCERT");
        create(keyword + "草稿", "上海", "CONCERT");
        var other = create(keyword + "展览", "北京", "EXHIBITION");
        for (JsonNode record : new JsonNode[]{first, second, other}) {
            mutate(post(admin(record.path("id").asText()) + "/publish"), Map.of("version", 0), 200);
        }
        mvc.perform(get("/api/v1/activities").param("keyword", keyword).param("city", "上海")
                        .param("category", "CONCERT").param("page", "1").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(second.path("id").asText()));
        mvc.perform(get("/api/v1/activities").param("keyword", keyword).param("city", "上海")
                        .param("category", "CONCERT").param("page", "2").param("size", "1"))
                .andExpect(jsonPath("$.data.items[0].id").value(first.path("id").asText()));
        mvc.perform(get("/api/v1/admin/activities").with(user("admin").roles("ADMIN")).param("keyword", keyword))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(4));
    }

    @Test
    void paginationAndActivityPayloadAreValidated() throws Exception {
        mvc.perform(get("/api/v1/activities").param("size", "51")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/activities").param("page", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/activities").param("category", "UNKNOWN")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/activities/-1")).andExpect(status().isBadRequest());
        mutate(post("/api/v1/admin/activities"), activity(" ", "上海", "CONCERT", 0), 400);
        var invalidCover = new java.util.HashMap<>(activity("封面校验", "上海", "CONCERT", 0));
        invalidCover.put("coverUrl", "javascript:alert(1)");
        mutate(post("/api/v1/admin/activities"), invalidCover, 400);
    }

    @Test
    void sessionsAreStoredInUtcSortedAndCancelledWithVersionControl() throws Exception {
        String id = create("场次管理", "上海", "STAGE").path("id").asText();
        var late = data(mutate(post(admin(id) + "/sessions"), session("晚场", "2030-05-01T20:00:00+08:00", "2030-05-01T22:00:00+08:00", "2030-04-01T10:00:00+08:00", 0), 200));
        var early = data(mutate(post(admin(id) + "/sessions"), session("午场", "2030-05-01T14:00:00+08:00", "2030-05-01T16:00:00+08:00", "2030-04-01T10:00:00+08:00", 0), 200));
        assertThat(late.path("startsAt").asText()).isEqualTo("2030-05-01T12:00:00Z");
        mutate(post(admin(id) + "/publish"), Map.of("version", 0), 200);
        mvc.perform(get("/api/v1/activities/" + id + "/sessions"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(early.path("id").asText()))
                .andExpect(jsonPath("$.data[1].id").value(late.path("id").asText()));
        String sessionPath = "/api/v1/admin/sessions/" + late.path("id").asText();
        mutate(put(sessionPath), session("修改晚场", "2030-05-01T20:00:00+08:00", "2030-05-01T22:30:00+08:00", "2030-04-01T10:00:00+08:00", 0), 200);
        mutate(post(sessionPath + "/cancel"), Map.of("version", 0), 409);
        mutate(post(sessionPath + "/cancel"), Map.of("version", 1), 200);
        mutate(post(sessionPath + "/cancel"), Map.of("version", 2), 409);
        mutate(put(sessionPath), session("不能恢复", "2030-05-01T20:00:00+08:00", "2030-05-01T22:00:00+08:00", "2030-04-01T10:00:00+08:00", 2), 409);
        mvc.perform(get("/api/v1/activities/" + id + "/sessions"))
                .andExpect(jsonPath("$.data[1].status").value("CANCELLED"));
    }

    @Test
    void sessionTimeRelationsAndMissingParentAreRejected() throws Exception {
        String id = create("场次校验", "上海", "STAGE").path("id").asText();
        mutate(post(admin(id) + "/sessions"), session("时间错误", "2030-05-01T20:00:00+08:00", "2030-05-01T19:00:00+08:00", "2030-04-01T10:00:00+08:00", 0), 400);
        mutate(post(admin(id) + "/sessions"), session("开售错误", "2030-05-01T20:00:00+08:00", "2030-05-01T22:00:00+08:00", "2030-05-01T20:00:00+08:00", 0), 400);
        mutate(post(admin(id) + "/sessions"), session("没有时区", "2030-05-01T20:00:00", "2030-05-01T22:00:00+08:00", "2030-04-01T10:00:00+08:00", 0), 400);
        mutate(post(admin("899999999") + "/sessions"), session("不存在活动", "2030-05-01T20:00:00+08:00", "2030-05-01T22:00:00+08:00", "2030-04-01T10:00:00+08:00", 0), 404);
    }

    @Test
    void adminRequiresIdentityRoleAndCsrf() throws Exception {
        String body = json.writeValueAsString(activity("权限校验", "上海", "CONCERT", 0));
        mvc.perform(post("/api/v1/admin/activities").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mvc.perform(post("/api/v1/admin/activities").with(user("ordinary").roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/activities").with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/session").with(httpBasic("integration-admin", TEST_PASSWORD)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.csrfToken").isNotEmpty())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/v1/admin/session").with(httpBasic("integration-admin", "invalid")))
                .andExpect(status().isUnauthorized());
    }

    private JsonNode create(String title, String city, String category) throws Exception {
        return data(mutate(post("/api/v1/admin/activities"), activity(title, city, category, 0), 200));
    }

    private Map<String, Object> activity(String title, String city, String category, int version) {
        return Map.of("title", title, "city", city, "category", category, "venue", "测试场馆", "summary", "中文测试简介", "description", "中文测试详情", "coverUrl", "", "version", version);
    }

    private Map<String, Object> session(String title, String start, String end, String sale, int version) {
        return Map.of("title", title, "startsAt", start, "endsAt", end, "saleStartsAt", sale, "version", version);
    }

    private String mutate(MockHttpServletRequestBuilder request, Object body, int expected) throws Exception {
        return mvc.perform(request.with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private JsonNode data(String body) throws Exception { return json.readTree(body).path("data"); }
    private String admin(String id) { return "/api/v1/admin/activities/" + id; }
}
