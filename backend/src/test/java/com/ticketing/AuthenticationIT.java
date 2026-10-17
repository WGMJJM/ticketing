package com.ticketing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.auth.dto.RegisterRequest;
import com.ticketing.auth.service.AccountService;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 通过嵌入式Tomcat的真实网络、Cookie、Session与CSRF验证数据库认证，不使用模拟身份。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.servlet.session.cookie.secure=false", "ticketing.admin-bootstrap.enabled=false"})
class AuthenticationIT {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwords;
    @Autowired private AccountService accounts;
    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> activityIds = new ArrayList<>();
    private final String password = UUID.randomUUID().toString();

    @AfterEach
    void cleanup() {
        for (Long id : activityIds) {
            jdbc.update("DELETE t FROM ticket_sku t JOIN activity_session s ON t.session_id=s.id WHERE s.activity_id=?", id);
            jdbc.update("DELETE FROM activity_session WHERE activity_id=?", id);
            jdbc.update("DELETE FROM activity WHERE id=?", id);
        }
        for (Long id : userIds) jdbc.update("DELETE FROM user_account WHERE id=?", id);
    }

    @Test
    void registrationStoresBcryptAndRejectsCaseInsensitiveDuplicates() throws Exception {
        Browser browser = new Browser();
        String name = name();
        JsonNode user = register(browser, name);
        assertThat(user.path("id").isTextual()).isTrue();
        assertThat(user.path("role").asText()).isEqualTo("USER");
        String hash = jdbc.queryForObject("SELECT password_hash FROM user_account WHERE id=?", String.class, user.path("id").asLong());
        assertThat(hash).startsWith("$2").isNotEqualTo(password);
        assertThat(passwords.matches(password, hash)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM user_account WHERE id=?", String.class, user.path("id").asLong())).isEqualTo("ACTIVE");
        browser.json("POST", "/auth/register", Map.of("username", name, "password", password), 409);
        browser.json("POST", "/auth/register", Map.of("username", name.toUpperCase(), "password", password), 409);
        browser.get("/auth/me", 401);
    }

    @Test
    void rejectsInvalidRegistrationAndPrivilegeInjection() throws Exception {
        Browser browser = new Browser();
        browser.csrf();
        for (String invalid : List.of("ab", "9starts", "has space", "中文用户名", "a".repeat(33))) {
            browser.json("POST", "/auth/register", Map.of("username", invalid, "password", password), 400);
        }
        for (String invalid : List.of("short", "a".repeat(73), "密".repeat(25), "valid\npassword")) {
            browser.json("POST", "/auth/register", Map.of("username", name(), "password", invalid), 400);
        }
        for (String field : List.of("role", "status", "id", "userId", "passwordHash")) {
            String username = name();
            var body = new HashMap<String, Object>(Map.of("username", username, "password", password));
            body.put(field, "ADMIN");
            browser.json("POST", "/auth/register", body, 400);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_account WHERE username=?", Integer.class, username)).isZero();
        }
    }

    @Test
    void badPasswordUnknownAndDisabledAccountsCannotLogin() throws Exception {
        Browser browser = new Browser();
        String name = name();
        JsonNode user = register(browser, name);
        browser.login(name, "wrong-password", 401);
        browser.login(name(), password, 401);
        browser.login(name, "a".repeat(100), 401);
        browser.get("/auth/me", 401);
        jdbc.update("UPDATE user_account SET status='DISABLED' WHERE id=?", user.path("id").asLong());
        JsonNode error = browser.login(name, password, 401);
        assertThat(error.path("message").asText()).contains("禁用");
        browser.get("/auth/me", 401);
    }

    @Test
    void sessionSurvivesRequestsRotatesAndLogoutInvalidatesOldCookie() throws Exception {
        Browser browser = new Browser();
        String name = name();
        JsonNode user = register(browser, name);
        String anonymousCookie = browser.cookie();
        String oldToken = browser.token;
        browser.login(name.toUpperCase(), password, 200);
        String loggedCookie = browser.cookie();
        assertThat(loggedCookie).isNotEqualTo(anonymousCookie);
        browser.get("/auth/me", 200);
        JsonNode current = browser.get("/auth/me?userId=999999", 200).path("data");
        assertThat(current).isEqualTo(user);
        assertThat(current.has("passwordHash")).isFalse();
        new Browser(anonymousCookie).get("/auth/me", 401);
        // 登录使旧CSRF失效；使用新令牌后才可执行退出。
        browser.token = oldToken;
        browser.json("POST", "/auth/logout", null, 403);
        browser.csrf();
        browser.json("POST", "/auth/logout", null, 200);
        browser.get("/auth/me", 401);
        new Browser(loggedCookie).get("/auth/me", 401);
        new Browser(loggedCookie).get("/admin/activities", 401);
        browser.csrf();
        browser.login(name, password, 200);
        browser.get("/auth/me", 200);
    }

    @Test
    void anonymousUserAndBasicCredentialsCannotAccessAdministration() throws Exception {
        Browser browser = new Browser();
        browser.get("/auth/me", 401);
        browser.get("/admin/activities", 401);
        String name = name();
        register(browser, name);
        String basic = java.util.Base64.getEncoder().encodeToString((name + ":" + password).getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(uri("/admin/activities")).header("Authorization", "Basic " + basic).GET().build();
        assertThat(browser.http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        browser.login(name, password, 200);
        browser.csrf();
        browser.get("/admin/activities", 403);
        browser.json("POST", "/admin/activities", activity(), 403);
    }

    @Test
    void everyWriteRequiresValidCsrfAndCookiesHaveSecurityAttributes() throws Exception {
        Browser browser = new Browser();
        var csrfResponse = browser.send("GET", "/auth/csrf", null, null, 200);
        String cookie = csrfResponse.headers().firstValue("set-cookie").orElseThrow();
        assertThat(cookie).contains("HttpOnly", "SameSite=Lax").doesNotContain("; Secure");
        browser.json("POST", "/auth/register", Map.of("username", name(), "password", password), 403);
        browser.login(name(), password, 403);
        browser.json("POST", "/auth/logout", null, 403);
        browser.token = "invalid";
        browser.json("POST", "/auth/register", Map.of("username", name(), "password", password), 403);
        browser.login(name(), password, 403);
        browser.json("POST", "/auth/logout", null, 403);
        String admin = admin();
        browser.csrf();
        browser.login(admin, password, 200);
        browser.token = "";
        browser.json("POST", "/admin/activities", activity(), 403);
        browser.token = "invalid";
        browser.json("POST", "/admin/activities", activity(), 403);
        browser.get("/auth/me", 200);
    }

    @Test
    void realAdminSessionManagesActivitiesSessionsAndTickets() throws Exception {
        Browser browser = new Browser();
        browser.csrf();
        browser.login(admin(), password, 200);
        browser.csrf();
        JsonNode activity = browser.json("POST", "/admin/activities", activity(), 200).path("data");
        String activityId = activity.path("id").asText();
        activityIds.add(activity.path("id").asLong());
        browser.json("POST", "/admin/activities/" + activityId + "/publish", Map.of("version", 0), 200);
        JsonNode session = browser.json("POST", "/admin/activities/" + activityId + "/sessions",
                Map.of("title", "真实会话测试场次", "startsAt", "2035-06-01T10:00:00Z",
                        "endsAt", "2035-06-01T12:00:00Z", "saleStartsAt", "2025-01-01T00:00:00Z"), 200).path("data");
        String sessionId = session.path("id").asText();
        var ticketBody = new HashMap<String, Object>(Map.of("name", "真实会话测试票档", "priceCents", "12345", "totalStock", 10,
                "saleStartsAt", "2025-02-01T00:00:00Z", "saleEndsAt", "2035-05-01T00:00:00Z", "maxQuantityPerOrder", 4));
        JsonNode ticket = browser.json("POST", "/admin/sessions/" + sessionId + "/tickets", ticketBody, 200).path("data");
        String ticketPath = "/admin/tickets/" + ticket.path("id").asText();
        browser.json("POST", ticketPath + "/publish", Map.of("version", 0), 200);
        ticketBody.put("totalStock", 12);
        ticketBody.put("version", 1);
        assertThat(browser.json("PUT", ticketPath, ticketBody, 200).path("data").path("availableStock").asInt()).isEqualTo(12);
        browser.get("/admin/activities/" + activityId, 200);
        browser.get("/admin/activities/" + activityId + "/sessions", 200);
        browser.get("/admin/sessions/" + sessionId + "/tickets", 200);
        browser.get("/activities/" + activityId + "/sessions/" + sessionId + "/tickets", 200);
        browser.json("POST", ticketPath + "/offline", Map.of("version", 2), 200);
        browser.json("POST", "/admin/sessions/" + sessionId + "/cancel", Map.of("version", 0), 200);
        browser.json("POST", "/admin/activities/" + activityId + "/offline", Map.of("version", 1), 200);
    }

    @Test
    void disablingLoggedInUserInvalidatesSessionAndOldCookieCannotRecover() throws Exception {
        Browser browser = new Browser();
        String username = name();
        JsonNode user = register(browser, username);
        browser.login(username, password, 200);
        String oldCookie = browser.cookie();
        jdbc.update("UPDATE user_account SET status='DISABLED' WHERE id=?", user.path("id").asLong());
        browser.get("/activities", 200);
        JsonNode error = browser.get("/auth/me", 401);
        assertThat(error.path("code").asText()).isEqualTo("ACCOUNT_UNAVAILABLE");
        jdbc.update("UPDATE user_account SET status='ACTIVE' WHERE id=?", user.path("id").asLong());
        new Browser(oldCookie).get("/auth/me", 401);
        browser.csrf();
        browser.login(username, password, 200);
        assertThat(browser.get("/auth/me", 200).path("data").path("id").asText()).isEqualTo(user.path("id").asText());
    }

    @Test
    void disablingAdminBlocksDirectWriteWithoutCallingMeFirst() throws Exception {
        Browser browser = new Browser();
        String username = admin();
        browser.csrf();
        browser.login(username, password, 200);
        browser.csrf();
        String oldCookie = browser.cookie();
        int count = jdbc.queryForObject("SELECT COUNT(*) FROM activity", Integer.class);
        jdbc.update("UPDATE user_account SET status='DISABLED' WHERE username=?", username);
        assertThat(browser.json("POST", "/admin/activities", activity(), 401).path("code").asText()).isEqualTo("ACCOUNT_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM activity", Integer.class)).isEqualTo(count);
        jdbc.update("UPDATE user_account SET status='ACTIVE' WHERE username=?", username);
        new Browser(oldCookie).get("/admin/activities", 401);
    }

    @Test
    void revokingAdminRoleBlocksActivityAndTicketWritesAndRefreshesSessionIdentity() throws Exception {
        Browser browser = new Browser();
        String username = admin();
        browser.csrf();
        browser.login(username, password, 200);
        browser.csrf();
        String cookie = browser.cookie();
        JsonNode activity = browser.json("POST", "/admin/activities", activity(), 200).path("data");
        activityIds.add(activity.path("id").asLong());
        JsonNode session = browser.json("POST", "/admin/activities/" + activity.path("id").asText() + "/sessions",
                Map.of("title", "撤权测试场次", "startsAt", "2035-06-01T10:00:00Z", "endsAt", "2035-06-01T12:00:00Z",
                        "saleStartsAt", "2025-01-01T00:00:00Z"), 200).path("data");
        JsonNode ticket = browser.json("POST", "/admin/sessions/" + session.path("id").asText() + "/tickets",
                Map.of("name", "撤权测试票档", "priceCents", "10000", "totalStock", 10, "saleStartsAt", "2025-02-01T00:00:00Z",
                        "saleEndsAt", "2035-05-01T00:00:00Z", "maxQuantityPerOrder", 4), 200).path("data");
        String activityPath = "/admin/activities/" + activity.path("id").asText();
        String ticketPath = "/admin/tickets/" + ticket.path("id").asText();
        jdbc.update("UPDATE user_account SET role='USER' WHERE username=?", username);
        browser.json("POST", activityPath + "/publish", Map.of("version", 0), 403);
        browser.json("POST", ticketPath + "/publish", Map.of("version", 0), 403);
        browser.get("/admin/activities", 403);
        assertThat(browser.get("/auth/me", 200).path("data").path("role").asText()).isEqualTo("USER");
        assertThat(browser.cookie()).isEqualTo(cookie);
        assertThat(jdbc.queryForObject("SELECT status FROM activity WHERE id=?", String.class, activity.path("id").asLong())).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT status FROM ticket_sku WHERE id=?", String.class, ticket.path("id").asLong())).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT version FROM ticket_sku WHERE id=?", Integer.class, ticket.path("id").asLong())).isZero();
        jdbc.update("UPDATE user_account SET role='ADMIN' WHERE username=?", username);
        browser.json("POST", activityPath + "/publish", Map.of("version", 0), 200);
        browser.json("POST", ticketPath + "/publish", Map.of("version", 0), 200);
        assertThat(browser.get("/auth/me", 200).path("data").path("role").asText()).isEqualTo("ADMIN");
    }

    @Test
    void deletedAccountCannotKeepUsingAuthenticatedSession() throws Exception {
        Browser browser = new Browser();
        String username = name();
        JsonNode user = register(browser, username);
        browser.login(username, password, 200);
        jdbc.update("DELETE FROM user_account WHERE id=?", user.path("id").asLong());
        browser.get("/auth/me", 401);
        browser.get("/auth/me", 401);
    }

    @Test
    void concurrentRegistrationsHaveOneDatabaseWinner() throws Exception {
        String username = name();
        Browser first = new Browser();
        Browser second = new Browser();
        first.csrf();
        second.csrf();
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var results = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (Browser browser : List.of(first, second)) {
                results.add(pool.submit(() -> {
                    start.await();
                    var request = HttpRequest.newBuilder(uri("/auth/register")).header("Content-Type", "application/json")
                            .header("X-CSRF-TOKEN", browser.token).POST(HttpRequest.BodyPublishers.ofString(
                                    json.writeValueAsString(Map.of("username", username, "password", password)))).build();
                    return browser.http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
                }));
            }
            start.countDown();
            assertThat(List.of(results.get(0).get(15, java.util.concurrent.TimeUnit.SECONDS),
                    results.get(1).get(15, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_account WHERE username=?", Integer.class, username)).isEqualTo(1);
        } finally {
            userIds.addAll(jdbc.queryForList("SELECT id FROM user_account WHERE username=?", Long.class, username));
        }
    }

    @Test
    void adminInitializationCannotPromoteUserOrResetExistingAdminPassword() throws Exception {
        String username = name();
        register(new Browser(), username);
        assertThatThrownBy(() -> accounts.initializeAdmin(new RegisterRequest(username, password))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT role FROM user_account WHERE username=?", String.class, username)).isEqualTo("USER");
        String admin = admin();
        accounts.initializeAdmin(new RegisterRequest(admin, UUID.randomUUID().toString()));
        String hash = jdbc.queryForObject("SELECT password_hash FROM user_account WHERE username=?", String.class, admin);
        assertThat(passwords.matches(password, hash)).isTrue();
    }

    private Map<String, Object> activity() {
        return Map.of("title", "真实Session认证测试活动", "category", "CONCERT", "city", "上海", "venue", "测试场地", "summary", "测试简介", "description", "测试详情");
    }
    private JsonNode register(Browser browser, String name) throws Exception {
        browser.csrf();
        JsonNode user = browser.json("POST", "/auth/register", Map.of("username", name, "password", password), 200).path("data");
        userIds.add(user.path("id").asLong());
        return user;
    }
    private String admin() {
        String name = name();
        accounts.initializeAdmin(new RegisterRequest(name, password));
        userIds.add(jdbc.queryForObject("SELECT id FROM user_account WHERE username=?", Long.class, name));
        return name;
    }
    private String name() { return "auth_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24); }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + "/api/v1" + path); }

    private class Browser {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
        private String token = "";
        private String fixedCookie;
        Browser() { }
        Browser(String cookie) { fixedCookie = cookie; }
        String cookie() { return cookies.getCookieStore().getCookies().stream().filter(cookie -> "JSESSIONID".equals(cookie.getName())).map(cookie -> "JSESSIONID=" + cookie.getValue()).findFirst().orElseThrow(); }
        JsonNode get(String path, int expected) throws Exception { return body(send("GET", path, null, null, expected)); }
        void csrf() throws Exception { token = get("/auth/csrf", 200).path("data").path("token").asText(); }
        JsonNode login(String username, String password, int expected) throws Exception {
            String form = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8) + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
            return body(send("POST", "/auth/login", form, "application/x-www-form-urlencoded", expected));
        }
        JsonNode json(String method, String path, Object data, int expected) throws Exception {
            return body(send(method, path, data == null ? "" : json.writeValueAsString(data), "application/json", expected));
        }
        HttpResponse<String> send(String method, String path, String data, String contentType, int expected) throws Exception {
            var builder = HttpRequest.newBuilder(uri(path)).timeout(java.time.Duration.ofSeconds(15)).header("Accept", "application/json");
            if (fixedCookie != null) builder.header("Cookie", fixedCookie);
            if (!token.isEmpty()) builder.header("X-CSRF-TOKEN", token);
            if (contentType != null) builder.header("Content-Type", contentType);
            var response = http.send(builder.method(method, data == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(data)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("%s %s", method, path).isEqualTo(expected);
            if (path.startsWith("/auth/")) assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
            assertThat(response.body()).doesNotContain(password, "passwordHash", "JSESSIONID", "stackTrace");
            return response;
        }
        JsonNode body(HttpResponse<String> response) throws Exception { return json.readTree(response.body()); }
    }
}
