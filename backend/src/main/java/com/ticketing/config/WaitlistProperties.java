package com.ticketing.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("ticketing.waitlist")
public record WaitlistProperties(
        @DefaultValue("60") @Min(1) int maxWaitMinutes,
        @DefaultValue("50") @Min(1) @Max(500) int dispatchMaxSteps,
        @DefaultValue("100") @Min(1) @Max(500) int compensationBatchSize) {}
