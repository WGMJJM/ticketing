package com.ticketing;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/** 生产HTTPS配置独立验证，避免本地HTTP例外掩盖Secure属性。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.servlet.session.cookie.secure=true", "ticketing.admin-bootstrap.enabled=false"})
class SessionCookieIT {
    @LocalServerPort private int port;

    @Test
    void httpsConfigurationIssuesSecureHttpOnlySameSiteCookie() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/csrf"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("set-cookie").orElseThrow()).contains("Secure", "HttpOnly", "SameSite=Lax");
        }
    }
}
