package com.ticketing.admission;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("ticketing.purchase.admission-limit")
public record AdmissionLimitProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("20") @Min(1) @Max(10000) int globalRate,
        @DefaultValue("40") @Min(1) @Max(20000) int globalBurst,
        @DefaultValue("10") @Min(1) @Max(10000) int ticketRate,
        @DefaultValue("20") @Min(1) @Max(20000) int ticketBurst,
        @DefaultValue("2") @Min(1) @Max(60) int unavailableRetrySeconds,
        @DefaultValue("purchase") @Pattern(regexp = "[A-Za-z0-9_-]{1,32}") String namespace) { }
