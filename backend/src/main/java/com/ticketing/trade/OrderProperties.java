package com.ticketing.trade;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("ticketing.order")
public record OrderProperties(
        @DefaultValue("4") @Min(1) int maxPerUserSession,
        @DefaultValue("15") @Min(1) int paymentWindowMinutes,
        @DefaultValue @Valid Expiry expiry) {
    public record Expiry(@DefaultValue("100") @Min(1) @Max(500) int batchSize) {}
}
