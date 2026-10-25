package com.ticketing.security;

import com.ticketing.config.ClientAddressProperties;
import com.ticketing.config.ClientAddressProperties.Mode;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientAddressResolverTest {
    private ClientAddressResolver resolver(Mode mode, String... proxies) {
        return new ClientAddressResolver(new ClientAddressProperties(mode, List.of(proxies), 8, 1024));
    }
    private MockHttpServletRequest request(String remote, String forwarded) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(remote);
        if (forwarded != null) request.addHeader("X-Forwarded-For", forwarded);
        request.addHeader("X-Real-IP", "192.0.2.99");
        request.addHeader("Forwarded", "for=192.0.2.99");
        return request;
    }
    @Test
    void directModeAndUntrustedSocketsIgnoreAllHeaders() {
        assertThat(resolver(Mode.DIRECT).resolve(request("198.51.100.1", "malformed"))).isEqualTo("198.51.100.1");
        assertThat(resolver(Mode.TRUSTED_PROXY, "10.0.0.0/8").resolve(request("198.51.100.1", "192.0.2.9"))).isEqualTo("198.51.100.1");
    }
    @Test
    void trustedChainStopsAtNearestUntrustedHopAndNormalizesIpv6() {
        var resolver = resolver(Mode.TRUSTED_PROXY, "10.0.0.0/8", "2001:db8:1234::/48");
        assertThat(resolver.resolve(request("10.0.0.2", "192.0.2.9, 198.51.100.4, 10.1.0.3"))).isEqualTo("198.51.100.4");
        assertThat(resolver.resolve(request("10.0.0.2", "192.0.2.9, 10.1.0.3"))).isEqualTo("192.0.2.9");
        assertThat(resolver.resolve(request("2001:db8:1234::2", "2001:db8:5678::1, 2001:db8:1234::3")))
                .isEqualTo("2001:db8:5678:0:0:0:0:1");
        assertThat(resolver.resolve(request("10.0.0.2", "::ffff:192.0.2.9"))).isEqualTo("192.0.2.9");
    }
    @ParameterizedTest
    @ValueSource(strings = {"", "unknown", "localhost", "127.1", "0177.0.0.1", "192.0.2.1:80", "[2001:db8::1]", "192.0.2.999", "192.0.2.1,", "\"192.0.2.1\"", "fe80::1%eth0", "192.0.2.1\t"})
    void malformedTrustedHeadersNeverBecomeIdentities(String header) {
        assertThatThrownBy(() -> resolver(Mode.TRUSTED_PROXY, "10.0.0.0/8").resolve(request("10.0.0.1", header)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("安全来源信息无效");
    }
    @Test
    void missingRepeatedOversizeAndOverlongChainsFailClosed() {
        var resolver = resolver(Mode.TRUSTED_PROXY, "10.0.0.0/8");
        assertThatThrownBy(() -> resolver.resolve(request("10.0.0.1", null))).isInstanceOf(IllegalArgumentException.class);
        var duplicate = request("10.0.0.1", "192.0.2.1"); duplicate.addHeader("X-Forwarded-For", "192.0.2.2");
        assertThatThrownBy(() -> resolver.resolve(duplicate)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve(request("10.0.0.1", "192.0.2.1,".repeat(150)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve(request("10.0.0.1", "192.0.2.1,".repeat(8)+"192.0.2.1"))).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0/0", "::/0", "localhost", "10.0.0.1/33", "2001:db8::1/129", "10.0.0.1/", "*"})
    void invalidOrUniversalTrustRangesFailConfiguration(String range) {
        assertThatThrownBy(() -> resolver(Mode.TRUSTED_PROXY, range)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void proxyModesRequireExplicitNonconflictingConfiguration() {
        assertThatThrownBy(() -> resolver(Mode.TRUSTED_PROXY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver(Mode.DIRECT, "127.0.0.1")).isInstanceOf(IllegalArgumentException.class);
    }
}
