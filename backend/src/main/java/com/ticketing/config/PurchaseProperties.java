package com.ticketing.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("ticketing.purchase")
public record PurchaseProperties(
        @DefaultValue("ticketing") @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String namespace,
        @DefaultValue("200") @Min(1) @Max(10000) int capacity,
        @DefaultValue("120") @Min(5) @Max(900) int queueSeconds,
        @DefaultValue("64") @Min(1) @Max(256) int inFlightWindow,
        @DefaultValue("3000") @Min(100) @Max(10000) int confirmTimeoutMs,
        @DefaultValue("10") @Min(1) @Max(300) int recoverySeconds,
        @DefaultValue("100") @Min(1) @Max(100) int batchSize) {}
