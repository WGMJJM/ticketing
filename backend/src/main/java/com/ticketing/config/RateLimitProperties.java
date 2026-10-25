package com.ticketing.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("ticketing.security.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        String keySecret,
        @DefaultValue("default") @Pattern(regexp = "[A-Za-z0-9_-]{1,32}") String namespace,
        @DefaultValue("1") @Min(1) @Max(60) int unavailableRetrySeconds,
        @Valid Policy loginIp,
        @Valid Policy loginAccountIp,
        @Valid Policy registerIp,
        @Valid Policy orderUser,
        @Valid Policy waitlistUser,
        @Valid Policy csrfSessionIp) {

    public RateLimitProperties {
        loginIp = defaults(loginIp, 60, 60);
        loginAccountIp = defaults(loginAccountIp, 10, 60);
        registerIp = defaults(registerIp, 10, 600);
        orderUser = defaults(orderUser, 30, 10);
        waitlistUser = defaults(waitlistUser, 10, 60);
        csrfSessionIp = defaults(csrfSessionIp, 120, 60);
    }

    private static Policy defaults(Policy configured, int limit, int windowSeconds) {
        return new Policy(configured == null || configured.limit() == null ? limit : configured.limit(),
                configured == null || configured.windowSeconds() == null ? windowSeconds : configured.windowSeconds());
    }

    /** 校验布尔结果，不将无效密钥原文写入绑定失败诊断。 */
    @AssertTrue(message = "启用请求限流时须配置32至128字节的Base64环境密钥")
    public boolean isKeySecretValid() {
        if (!enabled) return true;
        try {
            int length = Base64.getDecoder().decode(keySecret == null ? "" : keySecret).length;
            return length >= 32 && length <= 128;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    @Override
    public String toString() {
        return "RateLimitProperties[enabled=" + enabled + ", namespace=" + namespace + "]";
    }

    public record Policy(@NotNull @Min(1) @Max(10000) Integer limit,
                         @NotNull @Min(1) @Max(3600) Integer windowSeconds) {}
}
