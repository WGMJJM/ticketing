package com.ticketing;

import com.ticketing.config.RateLimitProperties;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class RateLimitConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Properties.class);

    @Test
    void protectionDefaultsToEnabledAndRequiresIndependentSecret() {
        runner.run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("ticketing.security.rate-limit.key-secret=" + secret()).run(context -> {
            assertThat(context).hasNotFailed();
            var settings = context.getBean(RateLimitProperties.class);
            assertThat(settings.enabled()).isTrue();
            assertThat(settings.loginIp().limit()).isEqualTo(60);
            assertThat(settings.loginAccountIp().limit()).isEqualTo(10);
            assertThat(settings.registerIp().windowSeconds()).isEqualTo(600);
            assertThat(settings.orderUser().limit()).isEqualTo(30);
            assertThat(settings.waitlistUser().limit()).isEqualTo(10);
            assertThat(settings.csrfSessionIp().limit()).isEqualTo(120);
            assertThat(settings.toString()).doesNotContain(settings.keySecret());
        });
    }

    @Test
    void invalidThresholdWindowNamespaceAndSecretFailStartup() {
        for (String invalid : new String[] {"login-ip.limit=0", "login-account-ip.limit=10001",
                "register-ip.window-seconds=0", "order-user.window-seconds=3601", "waitlist-user.limit=-1",
                "namespace=unsafe:*", "unavailable-retry-seconds=61", "key-secret=short", "csrf-session-ip.limit=0"}) {
            runner.withPropertyValues("ticketing.security.rate-limit.key-secret=" + secret(),
                    "ticketing.security.rate-limit." + invalid).run(context -> assertThat(context).hasFailed());
        }
    }

    @Test
    void controlledTestCanExplicitlyDisableAndPartialPoliciesKeepDefaults() {
        runner.withPropertyValues("ticketing.security.rate-limit.enabled=false",
                "ticketing.security.rate-limit.order-user.limit=45").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RateLimitProperties.class).orderUser().windowSeconds()).isEqualTo(10);
            assertThat(context.getBean(RateLimitProperties.class).orderUser().limit()).isEqualTo(45);
        });
    }

    private static String secret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RateLimitProperties.class)
    static class Properties { }
}
