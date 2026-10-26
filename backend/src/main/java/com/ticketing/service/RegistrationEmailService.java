package com.ticketing.service;

import com.ticketing.config.RegistrationProperties;
import com.ticketing.exception.BusinessException;
import com.ticketing.security.RedisRateLimiter;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 仅服务注册用途；SMTP成功后激活，Lua一次性消费，不跨Redis/MySQL做补偿事务。 */
@Service
public class RegistrationEmailService {
    private static final Logger LOG = LoggerFactory.getLogger(RegistrationEmailService.class);
    private static final int VALID_SECONDS = 300;
    private static final int MAX_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>();
    static {
        SCRIPT.setLocation(new ClassPathResource("security/registration-email-code.lua"));
        SCRIPT.setResultType(Long.class);
    }
    private final StringRedisTemplate redis;
    private final ObjectProvider<JavaMailSender> senders;
    private final ObjectProvider<RedisRateLimiter> limiters;
    private final RegistrationProperties properties;
    private final SecretKeySpec secret;
    private final String smtpHost;

    public RegistrationEmailService(StringRedisTemplate redis, ObjectProvider<JavaMailSender> senders,
            ObjectProvider<RedisRateLimiter> limiters, RegistrationProperties properties,
            @Value("${spring.mail.host:}") String smtpHost) {
        this.redis = redis;
        this.senders = senders;
        this.limiters = limiters;
        this.properties = properties;
        this.smtpHost = smtpHost;
        secret = properties.emailEnabled()
                ? new SecretKeySpec(Base64.getDecoder().decode(properties.codeSecret()), "HmacSHA256") : null;
    }

    public void send(String email, String address) {
        requireConfigured();
        JavaMailSender sender = senders.getIfAvailable();
        RedisRateLimiter limiter = limiters.getIfAvailable();
        if (sender == null || limiter == null) throw unavailable();
        var decision = limiter.emailCode(address, email);
        if (decision.outcome() != RedisRateLimiter.Outcome.ALLOWED) {
            boolean limited = decision.outcome() == RedisRateLimiter.Outcome.LIMITED;
            throw new BusinessException(limited ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.SERVICE_UNAVAILABLE,
                    limited ? "TOO_MANY_REQUESTS" : "RATE_LIMIT_UNAVAILABLE",
                    limited ? "验证码发送过于频繁，请稍后重试" : "安全校验服务暂不可用，请稍后重试",
                    decision.retryAfterSeconds());
        }
        String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        String sendId = UUID.randomUUID().toString();
        String key = emailKey(email);
        if (execute(key, "prepare", sendId, digest("register-code\0" + email + "\0" + code),
                Integer.toString(VALID_SECONDS)) != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "EMAIL_CODE_SENDING", "验证码正在发送，请稍后重试");
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(properties.mailFrom());
            message.setTo(email);
            message.setSubject("票务交易平台注册验证码");
            message.setText("您的注册验证码为：" + code + "。5分钟内有效，请勿向他人提供。重新发送后旧验证码失效。");
            sender.send(message);
        } catch (MailException exception) {
            // 不输出SMTP异常正文、地址或邮件内容；只清理本次sendId，不能删除后续发送。
            LOG.warn("注册验证码邮件发送失败，错误类型 {}", exception.getClass().getSimpleName());
            abort(key, sendId);
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_SEND_FAILED", "验证码邮件发送失败，请稍后重新获取");
        }
        if (execute(key, "activate", sendId, Integer.toString(VALID_SECONDS), "") != 1) throw unavailable();
    }

    public void consume(String email, String code) {
        requireConfigured();
        long result = execute(emailKey(email), "consume", digest("register-code\0" + email + "\0" + code), "",
                Integer.toString(MAX_ATTEMPTS));
        if (result == 1) return;
        throw new BusinessException(HttpStatus.BAD_REQUEST, "EMAIL_CODE_INVALID",
                result == -2 ? "验证码错误次数已达上限，请重新获取" : "验证码不正确、已使用或已过期，请检查或重新获取");
    }

    private void abort(String key, String sendId) {
        try { execute(key, "abort", sendId, "", ""); }
        catch (BusinessException exception) {
            LOG.warn("失败邮件的临时验证记录待自然过期");
        }
    }

    private long execute(String key, String... args) {
        try {
            Long result = redis.execute(SCRIPT, List.of(key), (Object[]) args);
            if (result == null) throw unavailable();
            return result;
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    private String emailKey(String email) {
        return "ticketing:registration:v1:" + properties.namespace() + ":email-code:" + digest("register-email\0" + email);
    }

    private String digest(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(secret);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("验证码摘要算法不可用");
        }
    }

    private void requireConfigured() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("邮箱验证码与邮件发送不得位于数据库事务内");
        }
        if (!properties.emailEnabled() || smtpHost.isBlank()) throw unavailable();
    }

    private BusinessException unavailable() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "REGISTRATION_EMAIL_UNAVAILABLE",
                "注册邮箱验证服务暂不可用，请稍后重试");
    }
}
