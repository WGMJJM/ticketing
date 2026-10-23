package com.ticketing;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

/** 真实TCP来源为已配置的本机代理；伪造的最左侧地址不能越过最近不可信节点。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "server.servlet.session.cookie.secure=false","ticketing.admin-bootstrap.enabled=false",
        "ticketing.security.rate-limit.enabled=true","ticketing.security.client-address.mode=TRUSTED_PROXY",
        "ticketing.security.client-address.trusted-proxies=127.0.0.1/32,10.0.0.0/8",
        "ticketing.security.rate-limit.csrf-session-ip.limit=2","ticketing.security.rate-limit.csrf-session-ip.window-seconds=30",
        "ticketing.security.rate-limit.login-account-ip.limit=2","ticketing.security.rate-limit.login-account-ip.window-seconds=30",
        "ticketing.security.rate-limit.register-ip.limit=2","ticketing.security.rate-limit.register-ip.window-seconds=30"})
@Import(CsrfSessionIT.SessionConfiguration.class)
class TrustedProxyIT {
    static final String NAMESPACE="proxy_"+UUID.randomUUID().toString().replace("-","").substring(0,20);
    static final String SECRET=CsrfSessionIT.secret();
    @DynamicPropertySource static void settings(DynamicPropertyRegistry p) {
        p.add("ticketing.security.rate-limit.namespace",()->NAMESPACE);
        p.add("ticketing.security.rate-limit.key-secret",()->SECRET);
        p.add("spring.data.redis.host",()->"127.0.0.1");
        p.add("spring.data.redis.port",()->System.getenv().getOrDefault("RATE_LIMIT_TEST_REDIS_PORT","16379"));
    }
    @LocalServerPort int port;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper json;
    @Autowired CsrfSessionIT.SessionCounter sessions;
    @BeforeEach void before(){clear();sessions.resetCounts();}
    @AfterEach void after(){clear();sessions.clear();}
    void clear(){try(var keys=redis.scan(ScanOptions.scanOptions().match("ticketing:rate-limit:v1:"+NAMESPACE+":*").count(100).build())){while(keys.hasNext())redis.delete(keys.next());}}
    HttpResponse<String> call(HttpClient client,String path,String chain,String token,String form) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path));
        if(chain!=null) request.header("X-Forwarded-For",chain);
        request.header("X-Real-IP","203.0.113.99").header("Forwarded","for=203.0.113.99");
        if(token!=null)request.header("X-CSRF-TOKEN",token);
        if(form!=null)request.header("Content-Type",path.equals("/auth/register")?"application/json":"application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form));
        else request.GET();
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void differentClientsHaveIndependentSessionBudgetsAndSpoofedLeftmostEntriesDoNotResetThem() throws Exception {
        try(var client=HttpClient.newHttpClient()){
            for(String address:new String[]{"198.51.100.1","198.51.100.2","2001:db8::1"}){
                for(int i=0;i<2;i++)assertThat(call(client,"/auth/csrf",address+", 10.1.0.1",null,null).statusCode()).isEqualTo(200);
                var rejected=call(client,"/auth/csrf","192.0.2.99, "+address+", 10.1.0.1",null,null);
                assertThat(rejected.statusCode()).isEqualTo(429);assertThat(rejected.headers().allValues("Set-Cookie")).isEmpty();
            }
            assertThat(sessions.created.get()).isEqualTo(6);
        }
    }
    @Test void trustedSourceMissingOrMalformedHeaderFailsBeforeSessionCreation() throws Exception {
        try(var client=HttpClient.newHttpClient()){
            for(String value:new String[]{null,"","unknown","192.0.2.1,","[2001:db8::1]:80"}){
                var response=call(client,"/auth/csrf",value,null,null);
                assertThat(response.statusCode()).isEqualTo(400);
                assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
                assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("INVALID_CLIENT_ADDRESS");
            }
            assertThat(sessions.created.get()).isZero();
        }
    }
    @Test void loginAndRegistrationUseTheSameVerifiedClientBoundary() throws Exception {
        for(String address:new String[]{"198.51.100.10","198.51.100.11"}){
            try(var browser=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build()){
                String token=json.readTree(call(browser,"/auth/csrf",address,null,null).body()).path("data").path("token").asText();
                for(int i=0;i<2;i++) assertThat(call(browser,"/auth/login",address,token,"username=proxy_probe&password=invalid-test-value").statusCode()).isEqualTo(401);
                assertThat(call(browser,"/auth/login","192.0.2.99, "+address,token,"username=PROXY_PROBE&password=invalid-test-value").statusCode()).isEqualTo(429);
                for(int i=0;i<2;i++)assertThat(call(browser,"/auth/register",address,token,"{}").statusCode()).isEqualTo(400);
                assertThat(call(browser,"/auth/register","192.0.2.99, "+address,token,"{}").statusCode()).isEqualTo(429);
            }
        }
    }
}
