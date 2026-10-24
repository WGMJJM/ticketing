package com.ticketing.messaging;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** 仅购票命令队列的并发预算；默认与原全局配置一致，候补队列不受影响。 */
@Validated
@ConfigurationProperties("ticketing.purchase.consumer")
public record PurchaseConsumerProperties(
        @DefaultValue("2") @Min(1) @Max(16) int concurrency,
        @DefaultValue("1") @Min(1) @Max(64) int prefetch) { }
