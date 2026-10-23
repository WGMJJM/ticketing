package com.ticketing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.auth.AuthController;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/** 监听真实Tomcat Session创建与销毁，不用响应码代替资源增长证据。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "server.servlet.session.cookie.secure=false","ticketing.admin-bootstrap.enabled=false",
        "ticketing.security.rate-limit.enabled=true","ticketing.security.rate-limit.csrf-session-ip.limit=5",
        "ticketing.security.rate-limit.csrf-session-ip.window-seconds=3"})
@Import(CsrfSessionIT.SessionConfiguration.class)
class CsrfSessionIT {
    static final String NAMESPACE="csrf_"+UUID.randomUUID().toString().replace("-","").substring(0,20);
    static final String SECRET=secret();
    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry properties) {
        properties.add("ticketing.security.rate-limit.namespace",()->NAMESPACE);
        properties.add("ticketing.security.rate-limit.key-secret",()->SECRET);
        properties.add("spring.data.redis.host",()->"127.0.0.1");
        properties.add("spring.data.redis.port",()->System.getenv().getOrDefault("RATE_LIMIT_TEST_REDIS_PORT","16379"));
    }
    static String secret() { byte[] value=new byte[32];new SecureRandom().nextBytes(value);return Base64.getEncoder().encodeToString(value); }
    @LocalServerPort int port;
    @Autowired SessionCounter sessions;
    @Autowired ObjectMapper json;
    @MockitoSpyBean StringRedisTemplate redis;
    @MockitoSpyBean AuthController controller;

    @BeforeEach void before() { reset(redis);clear();sessions.resetCounts();clearInvocations(controller); }
    @AfterEach void after() { reset(redis);clear();sessions.clear(); }
    void clear() {
        try(var keys=redis.scan(ScanOptions.scanOptions().match("ticketing:rate-limit:v1:"+NAMESPACE+":*").count(100).build())) {
            while(keys.hasNext()) redis.delete(keys.next());
        }
    }
    URI uri(String path) { return URI.create("http://127.0.0.1:"+port+"/api/v1"+path); }
    HttpResponse<String> call(HttpClient client,String method,String path,String forwarded) throws Exception {
        var request=HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5));
        if(forwarded!=null) request.header("X-Forwarded-For",forwarded).header("X-Real-IP",forwarded).header("Forwarded","for="+forwarded);
        return client.send(request.method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
    }
    void rejected(HttpResponse<String> response,int status) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(Integer.parseInt(response.headers().firstValue("Retry-After").orElse("0"))).isBetween(1,3);
        if(!response.body().isEmpty()) assertThat(json.readTree(response.body()).path("code").asText())
                .isEqualTo(status==429?"TOO_MANY_REQUESTS":"RATE_LIMIT_UNAVAILABLE");
    }
    @Test void concurrentNoCookieRequestsCreateOnlyBudgetedSessionsAndNeverReachControllerWhenRejected() throws Exception {
        try(var client=HttpClient.newHttpClient();var pool=Executors.newFixedThreadPool(24)) {
            var start=new CountDownLatch(1);var futures=new ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
            for(int i=0;i<120;i++) futures.add(pool.submit(()->{start.await();return call(client,"GET","/auth/csrf",null);}));
            start.countDown();int allowed=0,rejected=0;
            for(var future:futures) {
                var response=future.get(10,TimeUnit.SECONDS);
                if(response.statusCode()==200) {allowed++;assertThat(response.headers().allValues("Set-Cookie")).hasSize(1);}
                else { rejected(response,429);rejected++; }
            }
            assertThat(allowed).isEqualTo(5);assertThat(rejected).isEqualTo(115);
            assertThat(sessions.created.get()).isEqualTo(5);assertThat(sessions.active.get()).isEqualTo(5);
            verify(controller,times(5)).csrf(any(CsrfToken.class));
        }
    }
    @Test void existingSessionAndOtherTabsReuseTheSessionDuringExhaustionAndRedisOutage() throws Exception {
        var cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        try(var browser=HttpClient.newBuilder().cookieHandler(cookies).build();var anonymous=HttpClient.newHttpClient()) {
            assertThat(call(browser,"GET","/auth/csrf",null).statusCode()).isEqualTo(200);
            for(int i=0;i<4;i++) assertThat(call(anonymous,"GET","/auth/csrf",null).statusCode()).isEqualTo(200);
            rejected(call(anonymous,"GET","/auth/csrf",null),429);
            for(int i=0;i<10;i++) {
                var response=call(browser,"GET","/auth/csrf",null);
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
            }
            try(var anotherTab=HttpClient.newBuilder().cookieHandler(cookies).build()) {
                assertThat(call(anotherTab,"GET","/auth/csrf",null).statusCode()).isEqualTo(200);
            }
            doThrow(new DataAccessResourceFailureException("test-only")).when(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class),anyList(),any(Object[].class));
            assertThat(call(browser,"GET","/auth/csrf",null).statusCode()).isEqualTo(200);
            rejected(call(anonymous,"GET","/auth/csrf",null),503);
            assertThat(call(anonymous,"GET","/activities",null).statusCode()).isEqualTo(200);
            assertThat(sessions.created.get()).isEqualTo(5);
        }
    }
    @Test void headRemainsForbiddenWithoutMaterializingTokenAndAnonymousUnsafeRequestsAreBounded() throws Exception {
        try(var client=HttpClient.newHttpClient()) {
            for(int i=0;i<6;i++) assertThat(call(client,"HEAD","/auth/csrf",null).statusCode()).isEqualTo(403);
            assertThat(sessions.created.get()).isZero();
            verify(controller,never()).csrf(any());
            clear();sessions.resetCounts();clearInvocations(controller);
            for(int i=0;i<5;i++) assertThat(call(client,"POST","/auth/login",null).statusCode()).isEqualTo(403);
            rejected(call(client,"POST","/auth/login",null),429);
            assertThat(sessions.created.get()).isEqualTo(5);
            verify(controller,never()).csrf(any());
        }
    }
    @Test void forgedHeadersAndInvalidCookiesCannotBypassDirectModeAndExpiryRestoresBudget() throws Exception {
        try(var client=HttpClient.newHttpClient()) {
            for(int i=0;i<5;i++) assertThat(call(client,"GET","/auth/csrf","192.0.2."+i).statusCode()).isEqualTo(200);
            rejected(call(client,"GET","/auth/csrf","198.51.100.1"),429);
            var invalid=HttpRequest.newBuilder(uri("/auth/csrf")).header("Cookie","JSESSIONID=invalid-test-cookie").GET().build();
            rejected(client.send(invalid,HttpResponse.BodyHandlers.ofString()),429);
            Thread.sleep(3100);
            assertThat(call(client,"GET","/auth/csrf",null).statusCode()).isEqualTo(200);
            assertThat(sessions.created.get()).isEqualTo(6);
            sessions.clear();assertThat(sessions.active.get()).isZero();assertThat(sessions.destroyed.get()).isEqualTo(6);
        }
    }
    @Test void redisFaultRejectsBeforeTokenMaterializationOrSessionCreation() throws Exception {
        doThrow(new DataAccessResourceFailureException("test-only")).when(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class),anyList(),any(Object[].class));
        try(var client=HttpClient.newHttpClient()) {
            rejected(call(client,"GET","/auth/csrf",null),503);
            rejected(call(client,"POST","/orders",null),503);
            assertThat(sessions.created.get()).isZero();verify(controller,never()).csrf(any());
            reset(redis);
            assertThat(call(client,"GET","/auth/csrf",null).statusCode()).isEqualTo(200);
            assertThat(sessions.created.get()).isEqualTo(1);
        }
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class SessionConfiguration {
        @Bean SessionCounter sessionCounter() {return new SessionCounter();}
        @Bean ServletListenerRegistrationBean<SessionCounter> sessionListener(SessionCounter counter) {return new ServletListenerRegistrationBean<>(counter);}
    }
    static class SessionCounter implements HttpSessionListener {
        final AtomicInteger created=new AtomicInteger(),destroyed=new AtomicInteger(),active=new AtomicInteger();
        final Queue<HttpSession> values=new ConcurrentLinkedQueue<>();
        @Override public void sessionCreated(HttpSessionEvent event) {created.incrementAndGet();active.incrementAndGet();values.add(event.getSession());}
        @Override public void sessionDestroyed(HttpSessionEvent event) {destroyed.incrementAndGet();active.decrementAndGet();}
        void clear() {
            HttpSession session;
            while((session=values.poll())!=null) {try {session.invalidate();} catch(IllegalStateException ignored) { /* 已经退出的测试会话。 */ }}
        }
        void resetCounts() {clear();created.set(0);destroyed.set(0);active.set(0);}
    }
}
