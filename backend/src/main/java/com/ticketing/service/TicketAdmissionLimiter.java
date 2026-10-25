package com.ticketing.service;

import com.ticketing.config.AdmissionLimitProperties;
import com.ticketing.exception.BusinessException;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 双桶同一Lua内先判定后扣减；令牌只表示请求速率，不表示库存或排队容量。 */
public class TicketAdmissionLimiter {
    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>();
    static {
        SCRIPT.setLocation(new ClassPathResource("admission/ticket-admission-v1.lua"));
        SCRIPT.setResultType(List.class);
    }
    private final StringRedisTemplate redis;
    private final AdmissionLimitProperties properties;
    private final MeterRegistry meters;
    public TicketAdmissionLimiter(StringRedisTemplate redis, AdmissionLimitProperties properties, MeterRegistry meters) {
        this.redis = redis; this.properties = properties; this.meters = meters;
    }
    public void require(long skuId) {
        if (!properties.enabled()) return;
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Redis准入不能位于数据库事务内");
        String outcome;
        long retry;
        long start = System.nanoTime();
        try {
            String prefix = "ticketing:purchase-admission:v1:" + properties.namespace() + ":";
            List<?> result = redis.execute(SCRIPT, List.of(prefix + "global", prefix + "sku:" + skuId),
                    Integer.toString(properties.globalRate()), Integer.toString(properties.globalBurst()),
                    Integer.toString(properties.ticketRate()), Integer.toString(properties.ticketBurst()));
            if (result == null || result.size() != 2 || !(result.getFirst() instanceof Number)
                    || !(result.get(1) instanceof Number)) throw new IllegalStateException("准入脚本返回异常");
            long code = ((Number) result.getFirst()).longValue();
            long millis = ((Number) result.get(1)).longValue();
            if (code < 0 || code > 2 || millis < 0 || millis > 1000) throw new IllegalStateException("准入脚本返回越界");
            outcome = code == 0 ? "allowed" : code == 1 ? "limited-global" : "limited-ticket";
            retry = Math.max(1, (millis + 999) / 1000);
        } catch (RuntimeException exception) {
            outcome = "unavailable";
            retry = properties.unavailableRetrySeconds();
        } finally { meters.timer("ticketing.purchase.admission.redis.duration").record(System.nanoTime() - start, TimeUnit.NANOSECONDS); }
        meters.counter("ticketing.purchase.admission.requests", "outcome", outcome).increment();
        if (outcome.equals("allowed")) return;
        if (outcome.equals("unavailable")) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "PURCHASE_ADMISSION_UNAVAILABLE", "购票入口暂不可用，请稍后重试", retry);
        boolean global = outcome.equals("limited-global");
        throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,
                global ? "PURCHASE_GLOBAL_BUSY" : "PURCHASE_TICKET_BUSY",
                global ? "购票申请较多，请稍后使用原申请重试" : "该票档申请较多，请稍后使用原申请重试", retry);
    }
}
