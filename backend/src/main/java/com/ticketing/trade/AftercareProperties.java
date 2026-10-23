package com.ticketing.trade;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("ticketing.order.aftercare")
public record AftercareProperties(@DefaultValue("100") @Min(1) @Max(500) int batchSize,
        @DefaultValue("5") @Min(2) @Max(10) int maxAttempts,
        @DefaultValue("30") @Min(1) @Max(3600) int retryDelaySeconds) {}
