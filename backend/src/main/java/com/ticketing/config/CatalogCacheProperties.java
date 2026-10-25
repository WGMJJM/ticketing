package com.ticketing.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("ticketing.catalog-cache")
public record CatalogCacheProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("2000") @Min(16) @Max(100000) int maximumEntries,
        @DefaultValue("15") @Min(1) @Max(30) int localSeconds,
        @DefaultValue("120") @Min(30) @Max(300) int redisSeconds,
        @DefaultValue("5") @Min(1) @Max(15) int negativeSeconds,
        @DefaultValue("8") @Min(1) @Max(32) int concurrentLoads,
        @DefaultValue("100") @Min(1) @Max(1000) int loadWaitMs,
        @DefaultValue("3") @Min(1) @Max(10) int listPages,
        @DefaultValue({"北京", "上海", "广州", "深圳", "杭州", "成都"}) List<String> listCities,
        @DefaultValue("catalog") @Pattern(regexp = "[A-Za-z0-9_-]{1,32}") String namespace) {
    public CatalogCacheProperties { listCities = listCities == null ? List.of() : List.copyOf(listCities); }
}
