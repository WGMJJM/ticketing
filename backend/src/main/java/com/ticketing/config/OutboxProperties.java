package com.ticketing.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** 保留既有配置前缀，避免包调整改变部署参数。 */
@Validated
@ConfigurationProperties("ticketing.waitlist.outbox")
public record OutboxProperties(
        @DefaultValue("100") @Min(1) @Max(100) int batchSize,
        @DefaultValue("5000") @Min(1) int confirmTimeoutMs,
        @DefaultValue("30") @Min(1) int leaseSeconds,
        @DefaultValue("10") @Min(1) int maxAttempts,
        @DefaultValue("2") @Min(1) int retryBaseSeconds,
        @DefaultValue("300") @Min(1) int retryMaxSeconds,
        @DefaultValue("16") @Min(1) @Max(64) int inFlightWindow) {
    @AssertTrue(message = "Outbox租约须超过确认超时并预留至少一秒落账时间")
    public boolean isLeaseRangeValid() {
        return leaseSeconds * 1000L > confirmTimeoutMs + 1000L;
    }
    @AssertTrue(message = "Outbox最大退避不能小于初始退避")
    public boolean isRetryRangeValid() {
        return retryMaxSeconds >= retryBaseSeconds;
    }
}
