package com.ticketing.security;

import com.ticketing.config.RateLimitProperties;
import com.ticketing.config.RateLimitProperties.Policy;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/** 只决定请求频率，不访问交易数据，也不开启数据库事务。 */
@Service
@ConditionalOnProperty(prefix = "ticketing.security.rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisRateLimiter {
    private static final Logger LOG = LoggerFactory.getLogger(RedisRateLimiter.class);
    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>();
    static {
        SCRIPT.setLocation(new ClassPathResource("security/request-rate-limit.lua"));
        SCRIPT.setResultType(List.class);
    }
    private final StringRedisTemplate redis;
    private final RateLimitProperties properties;
    private final SecretKeySpec key;
    private final MeterRegistry meters;
    private final AtomicBoolean unavailable = new AtomicBoolean();

    public RedisRateLimiter(StringRedisTemplate redis, RateLimitProperties properties, MeterRegistry meters) {
        this.redis = redis;
        this.properties = properties;
        this.meters = meters;
        this.key = new SecretKeySpec(Base64.getDecoder().decode(properties.keySecret()), "HmacSHA256");
    }

    public Decision login(String address, String username) {
        String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return evaluate("login", List.of(key("login-ip", address), key("login-account-ip", normalized + "\0" + address)),
                List.of(properties.loginIp(), properties.loginAccountIp()));
    }

    public Decision registration(String address) {
        return evaluate("register", List.of(key("register-ip", address)), List.of(properties.registerIp()));
    }

    /** IP、邮箱冷却与邮箱日窗口在原多KEY Lua中全部通过后才一起扣额。 */
    public Decision emailCode(String address, String email) {
        return evaluate("email-code", List.of(key("email-code-ip", address), key("email-code-cooldown", email),
                        key("email-code-daily", email)),
                List.of(properties.emailCodeIp(), properties.emailCodeCooldown(), properties.emailCodeDaily()));
    }

    public Decision csrfSession(String address) {
        return evaluate("csrf-session", List.of(key("csrf-session-ip", address)), List.of(properties.csrfSessionIp()));
    }

    public Decision order(long userId) {
        return evaluate("order", List.of(key("order-user", Long.toString(userId))), List.of(properties.orderUser()));
    }

    public Decision waitlist(long userId) {
        return evaluate("waitlist", List.of(key("waitlist-user", Long.toString(userId))), List.of(properties.waitlistUser()));
    }

    private String key(String category, String identity) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            String digest = HexFormat.of().formatHex(mac.doFinal((category + "\0" + identity).getBytes(StandardCharsets.UTF_8)));
            return "ticketing:rate-limit:v1:" + properties.namespace() + ":" + category + ":" + digest;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("请求限流摘要算法不可用");
        }
    }

    private Decision evaluate(String route, List<String> keys, List<Policy> policies) {
        List<String> arguments = new ArrayList<>();
        for (Policy policy : policies) {
            arguments.add(Integer.toString(policy.limit()));
            arguments.add(Long.toString(policy.windowSeconds().longValue() * 1000));
        }
        Decision decision;
        try {
            List<?> result = redis.execute(SCRIPT, keys, arguments.toArray());
            if (result == null || result.size() != 2) throw new IllegalStateException("请求限流脚本返回异常");
            boolean allowed = ((Number) result.getFirst()).longValue() == 1;
            long retryMillis = ((Number) result.get(1)).longValue();
            decision = new Decision(allowed ? Outcome.ALLOWED : Outcome.LIMITED, allowed ? 0 : Math.max(1, (retryMillis + 999) / 1000));
            if (unavailable.getAndSet(false)) LOG.info("请求频率校验服务已恢复");
        } catch (DataAccessException exception) {
            if (unavailable.compareAndSet(false, true)) {
                // 不打印异常正文/堆栈，避免连接信息或请求标识进入日志；故障期间只记录一次。
                LOG.warn("请求频率校验暂不可用，类别 {}，原因 {}", route, exception.getClass().getSimpleName());
            }
            decision = new Decision(Outcome.UNAVAILABLE, properties.unavailableRetrySeconds());
        }
        meters.counter("ticketing.security.rate.limit.requests", "route", route,
                "outcome", decision.outcome().name().toLowerCase(Locale.ROOT)).increment();
        return decision;
    }

    public enum Outcome { ALLOWED, LIMITED, UNAVAILABLE }
    public record Decision(Outcome outcome, long retryAfterSeconds) {}
}
