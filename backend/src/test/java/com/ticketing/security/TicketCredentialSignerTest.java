package com.ticketing.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.common.BusinessException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketCredentialSignerTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test
    void randomTicketsHaveIndependentReproducibleCredentialsAndDigests() throws Exception {
        var signer = signer(Map.of("1", key(32)), 1);
        String first = signer.credential(signer.newTicketNo(), 1);
        String second = signer.credential(signer.newTicketNo(), 1);
        assertThat(first).isNotEqualTo(second);
        var verified = signer.verify(first);
        assertThat(signer.credential(verified.ticketNo(), verified.keyVersion())).isEqualTo(first);
        assertThat(MessageDigest.isEqual(verified.digest(), signer.digest(first))).isTrue();
        assertThat(verified.digest()).hasSize(32);
    }
    @Test
    void rejectsUnsignedIdsMalformedAndTamperedCredentials() throws Exception {
        var signer = signer(Map.of("1", key(32)), 1);
        String credential = signer.credential(signer.newTicketNo(), 1);
        for (String value : new String[] {"1", "订单10001", "https://example.test/" + credential,
                credential.replace("TKT1.", "TKT2."), credential.replace(".1.", ".2."), credential.substring(0, credential.length() - 1),
                credential.substring(0, credential.lastIndexOf('.') + 1) + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32])}) {
            assertThatThrownBy(() -> signer.verify(value)).isInstanceOf(BusinessException.class);
        }
    }
    @Test
    void rotationRetainsOldCredentialsAndSignsNewTicketsWithCurrentVersion() throws Exception {
        String firstKey = key(32);
        var original = signer(Map.of("1", firstKey), 1);
        String old = original.credential(original.newTicketNo(), 1);
        var rotated = signer(Map.of("1", firstKey, "2", key(32)), 2);
        assertThat(rotated.verify(old).keyVersion()).isEqualTo(1);
        assertThat(rotated.credential(rotated.newTicketNo(), rotated.currentVersion())).startsWith("TKT1.2.");
        var retired = signer(Map.of("2", key(32)), 2);
        assertThatThrownBy(() -> retired.verify(old)).isInstanceOf(BusinessException.class);
    }
    @Test
    void wrongKeyCannotVerifyASignatureWithTheSameVersion() throws Exception {
        var first = signer(Map.of("1", key(32)), 1);
        var second = signer(Map.of("1", key(32)), 1);
        String credential = first.credential(first.newTicketNo(), 1);
        assertThatThrownBy(() -> second.verify(credential)).isInstanceOf(BusinessException.class);
    }
    @Test
    void missingMalformedWeakAndMissingCurrentKeysFailWithoutEchoingSecretValues() throws Exception {
        String small = key(16);
        for (String value : new String[] {"", "{}", "not-json", "{\"1\":\"bad-value\"}", "{\"1\":12345}",
                "{\"1\":\"" + key(32) + "\",\"1\":\"" + key(32) + "\"}",
                json.writeValueAsString(Map.of("1", key(32))) + " {}", json.writeValueAsString(Map.of("1", small))}) {
            assertThatThrownBy(() -> new TicketCredentialSigner(json, value, 1))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("电子票签名配置").hasNoCause();
        }
        assertThatThrownBy(() -> signer(Map.of("1", key(32)), 2)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> signer(Map.of("01", key(32)), 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> signer(Map.of("32768", key(32)), 32768)).isInstanceOf(IllegalStateException.class);
    }
    private TicketCredentialSigner signer(Map<String, String> keys, int version) throws Exception {
        return new TicketCredentialSigner(json, json.writeValueAsString(keys), version);
    }
    private String key(int length) { byte[] bytes = new byte[length]; new SecureRandom().nextBytes(bytes); return Base64.getEncoder().encodeToString(bytes); }
}
