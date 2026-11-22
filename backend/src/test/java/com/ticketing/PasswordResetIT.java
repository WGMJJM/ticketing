package com.ticketing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** 使用独立 Mailpit、Redis 和 MySQL 验证真实邮件和浏览器会话。 */
@EnabledIfEnvironmentVariable(named = "ACCOUNT_IT_SMTP_PORT", matches = "\\d+")
@EnabledIfEnvironmentVariable(named = "ACCOUNT_IT_MAIL_WEB_PORT", matches = "\\d+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.servlet.session.cookie.secure=false", "ticketing.admin-bootstrap.enabled=false",
        "ticketing.registration.email-enabled=true", "ticketing.registration.bloom-enabled=false",
        "ticketing.registration.mail-from=account-test@example.test", "ticketing.security.rate-limit.enabled=true",
        "ticketing.security.rate-limit.email-code-cooldown.window-seconds=2",
        "spring.mail.host=127.0.0.1", "spring.mail.properties.mail.smtp.auth=false",
        "spring.mail.properties.mail.smtp.starttls.enable=false", "spring.mail.properties.mail.smtp.starttls.required=false"
})
class PasswordResetIT {
    private static final String NAMESPACE = "account-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String CODE_SECRET = secret();
    private static final String LIMIT_SECRET = secret();
    private static final Pattern CODE = Pattern.compile("验证码为：([0-9]{6})");
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwords;
    @Autowired private StringRedisTemplate redis;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.port", () -> System.getenv("ACCOUNT_IT_SMTP_PORT"));
        registry.add("ticketing.registration.namespace", () -> NAMESPACE);
        registry.add("ticketing.security.rate-limit.namespace", () -> NAMESPACE);
        registry.add("ticketing.registration.code-secret", () -> CODE_SECRET);
        registry.add("ticketing.security.rate-limit.key-secret", () -> LIMIT_SECRET);
    }

    @Test
    void verifiedEmailRecoversPasswordOnceAndSignsOutOldSessions() throws Exception {
        String email = NAMESPACE + "@example.test";
        String absent = "absent-" + email;
        String username = NAMESPACE.replace('-', '_');
        String original = UUID.randomUUID().toString();
        String replacement = UUID.randomUUID().toString();
        String send = "/auth/password/reset-code";
        String reset = "/auth/password/reset";
        HttpClient mail = HttpClient.newHttpClient();
        try (var owner = new Browser(); var visitor = new Browser()) {
            visitor.request("POST", send, Map.of("email", email), 403);
            visitor.csrf();
            // 先领注册验证码，再在隔离库中插入已验证账号。两用途的验证码状态不能互相消费。
            visitor.request("POST", "/auth/email-code", Map.of("email", email), 200);
            String registrationCode = awaitCode(mail, email, "注册", 1);
            jdbc.update("INSERT INTO user_account (username,password_hash,role,status,email,email_verified_at,created_at,updated_at) "
                            + "VALUES (?,?,'USER','ACTIVE',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                    username, passwords.encode(original), email);
            assertThat(visitor.request("POST", reset,
                    Map.of("email", email, "code", registrationCode, "newPassword", replacement), 400)
                    .path("code").asText()).isEqualTo("EMAIL_CODE_INVALID");
            owner.csrf();
            owner.login(username, original, 200);
            JsonNode hidden = visitor.request("POST", send, Map.of("email", absent), 200);
            assertThat(visitor.request("POST", send, Map.of("email", absent), 429).path("code").asText())
                    .isEqualTo("TOO_MANY_REQUESTS");
            Thread.sleep(Duration.ofSeconds(2));
            JsonNode accepted = visitor.request("POST", send, Map.of("email", email), 200);
            assertThat(accepted).isEqualTo(hidden);
            String code = awaitCode(mail, email, "找回密码", 2);
            assertThat(mailList(mail).toString()).doesNotContain(absent);

            var data = Map.of("email", email, "code", code, "newPassword", replacement);
            visitor.request("POST", reset, data, 200);
            visitor.request("POST", reset, data, 400);
            owner.request("GET", "/auth/me", null, 401);
            owner.csrf();
            owner.login(username, original, 401);
            owner.login(username, replacement, 200);
            owner.request("GET", "/auth/me", null, 200);
        } finally {
            jdbc.update("DELETE FROM user_account WHERE username=?", username);
            mail.close();
        }
    }

    private String awaitCode(HttpClient mail, String email, String purpose, int activeCount) throws Exception {
        for (int retry = 0; retry < 100; retry++) {
            long active = redis.keys("ticketing:registration:v1:" + NAMESPACE + ":email-code:*").stream()
                    .filter(key -> "ACTIVE".equals(redis.opsForHash().get(key, "state"))).count();
            if (active >= activeCount) {
                for (JsonNode summary : mailList(mail)) {
                    if (!summary.path("Subject").asText().contains(purpose)
                            || !summary.path("To").toString().contains(email)) continue;
                    var message = mailGet(mail, "/api/v1/message/" + summary.path("ID").asText());
                    var match = CODE.matcher(message.path("Text").asText());
                    if (match.find()) return match.group(1);
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("未找到已激活的" + purpose + "邮件验证码");
    }

    private JsonNode mailList(HttpClient mail) throws Exception {
        return mailGet(mail, "/api/v1/messages").path("messages");
    }

    private JsonNode mailGet(HttpClient mail, String path) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + System.getenv("ACCOUNT_IT_MAIL_WEB_PORT") + path);
        return json.readTree(mail.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body());
    }

    private static String secret() {
        return Base64.getEncoder().encodeToString((UUID.randomUUID() + ":" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
    }

    private class Browser implements AutoCloseable {
        private final HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        private String token = "";

        void csrf() throws Exception {
            token = request("GET", "/auth/csrf", null, 200).path("data").path("token").asText();
        }

        void login(String username, String password, int expected) throws Exception {
            send("POST", "/auth/login", "username=" + username + "&password=" + password,
                    "application/x-www-form-urlencoded", expected);
        }

        JsonNode request(String method, String path, Object body, int expected) throws Exception {
            return send(method, path, body == null ? null : json.writeValueAsString(body), "application/json", expected);
        }

        JsonNode send(String method, String path, String body, String type, int expected) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", type);
            if (!token.isEmpty()) request.header("X-CSRF-TOKEN", token);
            var response = client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("%s %s", method, path).isEqualTo(expected);
            assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
            return json.readTree(response.body());
        }

        @Override
        public void close() { client.close(); }
    }
}
