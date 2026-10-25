package com.ticketing.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("ticketing.security.client-address")
public record ClientAddressProperties(@DefaultValue("DIRECT") @NotNull Mode mode,
        @Size(max = 32) List<String> trustedProxies,
        @DefaultValue("8") @Min(1) @Max(32) int maxForwardedHops,
        @DefaultValue("1024") @Min(64) @Max(4096) int maxForwardedHeaderLength) {
    public ClientAddressProperties {
        trustedProxies = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
    }
    public enum Mode { DIRECT, TRUSTED_PROXY }
    @Override
    public String toString() { return "ClientAddressProperties[mode=" + mode + ", proxyCount=" + trustedProxies.size() + "]"; }
}
