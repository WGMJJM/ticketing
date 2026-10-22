package com.ticketing.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("ticketing.checkin")
public record CheckinProperties(
        @DefaultValue("60") @Min(0) @Max(1440) int opensBeforeMinutes,
        @DefaultValue @Valid Backfill backfill) {
    public record Backfill(@DefaultValue("100") @Min(1) @Max(500) int batchSize) {}
}
