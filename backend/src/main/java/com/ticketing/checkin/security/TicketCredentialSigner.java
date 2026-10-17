package com.ticketing.checkin.security;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.ticketing.common.api.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 凭证只在签发响应和验证期间存在，不持久化明文，也不记录到日志。 */
@Component
public class TicketCredentialSigner {
    private static final String FORMAT = "TKT1";
    private static final Pattern CREDENTIAL = Pattern.compile("TKT1\\.[1-9][0-9]{0,4}\\.[A-Za-z0-9_-]{43}\\.[A-Za-z0-9_-]{43}");
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private final SecureRandom random = new SecureRandom();
    private final Map<Integer, SecretKeySpec> keys;
    private final int currentVersion;

    public TicketCredentialSigner(ObjectMapper json,
            @Value("${ticketing.checkin.credential-keys:}") String encodedKeys,
            @Value("${ticketing.checkin.current-key-version:1}") int currentVersion) {
        Map<Integer, SecretKeySpec> parsed = new HashMap<>();
        try (JsonParser parser = json.getFactory().createParser(encodedKeys)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode configured = json.readTree(parser);
            if (configured == null || !configured.isObject() || parser.nextToken() != null) throw new IllegalArgumentException();
            for (var entry : configured.properties()) {
                int version = Integer.parseInt(entry.getKey());
                if (!entry.getValue().isTextual()) throw new IllegalArgumentException();
                byte[] key = Base64.getDecoder().decode(entry.getValue().textValue());
                if (version < 1 || version > Short.MAX_VALUE || !Integer.toString(version).equals(entry.getKey())
                        || key.length < 32 || key.length > 128) {
                    throw new IllegalArgumentException();
                }
                parsed.put(version, new SecretKeySpec(key, "HmacSHA256"));
            }
            if (!parsed.containsKey(currentVersion)) throw new IllegalArgumentException();
        } catch (Exception exception) {
            // 不附带解析异常，防止环境变量原文进入启动日志。
            throw new IllegalStateException("电子票签名配置缺失或不合法，请配置当前版本及至少32字节的Base64密钥");
        }
        this.keys = Map.copyOf(parsed);
        this.currentVersion = currentVersion;
    }

    public int currentVersion() { return currentVersion; }

    public String newTicketNo() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }

    public String credential(String ticketNo, int version) {
        SecretKeySpec key = keys.get(version);
        if (key == null) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "TICKET_KEY_UNAVAILABLE", "该版本电子票密钥暂不可用，请联系管理员");
        }
        String body = FORMAT + "." + version + "." + ticketNo;
        return body + "." + URL_ENCODER.encodeToString(sign(body, key));
    }

    public Verified verify(String credential) {
        if (credential == null || !CREDENTIAL.matcher(credential).matches()) throw invalid();
        String[] parts = credential.split("\\.");
        int version = Integer.parseInt(parts[1]);
        SecretKeySpec key = keys.get(version);
        if (key == null) throw invalid();
        byte[] ticketNo = Base64.getUrlDecoder().decode(parts[2]);
        byte[] signature = Base64.getUrlDecoder().decode(parts[3]);
        // 只接受规范化编码，避免同一签名出现多种尾位表示。
        if (ticketNo.length != 32 || !URL_ENCODER.encodeToString(ticketNo).equals(parts[2])
                || !URL_ENCODER.encodeToString(signature).equals(parts[3])
                || !MessageDigest.isEqual(sign(FORMAT + "." + version + "." + parts[2], key), signature)) throw invalid();
        return new Verified(parts[2], version, digest(credential));
    }

    public byte[] digest(String credential) {
        try { return MessageDigest.getInstance("SHA-256").digest(credential.getBytes(StandardCharsets.US_ASCII)); }
        catch (GeneralSecurityException exception) { throw new IllegalStateException("运行环境缺少SHA-256"); }
    }

    private byte[] sign(String body, SecretKeySpec key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(body.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException exception) { throw new IllegalStateException("运行环境不支持电子票签名"); }
    }

    public static BusinessException invalid() {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_TICKET_CREDENTIAL", "电子票凭证无效，请确认二维码来源");
    }

    public record Verified(String ticketNo, int keyVersion, byte[] digest) {}
}
