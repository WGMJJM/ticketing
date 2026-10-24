package com.ticketing.activity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.ticketing.common.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 单实例公开目录缓存。每代使用不可复用的键，提交后的旧加载只能写入旧代。 */
@Component
public class CatalogCache {
    private final CatalogCacheProperties properties;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final MeterRegistry meters;
    private final Cache<String, Value> local;
    private final Semaphore loads;
    private final AtomicReference<String> generation = new AtomicReference<>(UUID.randomUUID().toString());
    private final AtomicLong redisRetryAt = new AtomicLong();

    public CatalogCache(CatalogCacheProperties properties, StringRedisTemplate redis, ObjectMapper json, MeterRegistry meters) {
        this.properties = properties;
        this.redis = redis;
        this.json = json;
        this.meters = meters;
        this.loads = new Semaphore(properties.concurrentLoads());
        this.local = Caffeine.newBuilder().maximumSize(properties.maximumEntries())
                .expireAfter(new Expiry<String, Value>() {
                    public long expireAfterCreate(String key, Value value, long now) { return localNanos(value); }
                    public long expireAfterUpdate(String key, Value value, long now, long duration) { return localNanos(value); }
                    public long expireAfterRead(String key, Value value, long now, long duration) { return duration; }
                }).build();
        meters.gauge("ticketing.catalog.loads.active", this, value -> properties.concurrentLoads() - value.loads.availablePermits());
        meters.gauge("ticketing.catalog.local.entries", local, Cache::estimatedSize);
    }

    public boolean cacheList(ActivityQuery query) {
        String city = query.city() == null ? "" : query.city().trim();
        return (query.keyword() == null || query.keyword().isBlank()) && query.page() <= properties.listPages()
                && (query.size() == 12 || query.size() == 20)
                && (city.isEmpty() || properties.listCities().contains(city));
    }

    public <T> T read(Kind kind, String identity, TypeReference<T> type, Supplier<T> loader) {
        // 权威事务及管理路径绝不借用展示缓存，包括事务内的预检。
        if (!properties.enabled() || TransactionSynchronizationManager.isActualTransactionActive()) return loader.get();
        String key = "ticketing:catalog:v1:" + properties.namespace() + ":" + generation.get() + ":" + kind.name() + ":" + identity;
        Value value = local.getIfPresent(key);
        if (value != null) count(kind, "l1");
        else value = local.get(key, ignored -> load(kind, key, loader)); // 同键合并，无后台线程池。
        if (value.code() != null) {
            count(kind, "negative");
            throw new BusinessException(HttpStatus.NOT_FOUND, value.code(), value.message());
        }
        try { return json.readValue(value.payload(), type); }
        catch (JsonProcessingException exception) {
            local.invalidate(key);
            throw new IllegalStateException("公开目录缓存类型不一致", exception);
        }
    }

    /** 此方法仅登记提交回调，回滚不会换代；换代无需Redis可用。 */
    public void invalidateAfterCommit() {
        if (!properties.enabled()) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) throw new IllegalStateException("目录失效必须关联写事务");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                generation.set(UUID.randomUUID().toString());
                meters.counter("ticketing.catalog.invalidations").increment();
            }
        });
    }

    private <T> Value load(Kind kind, String key, Supplier<T> loader) {
        boolean acquired = false;
        try {
            acquired = loads.tryAcquire(properties.loadWaitMs(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                count(kind, "busy");
                throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "CATALOG_BUSY", "查询繁忙，请稍后重试");
            }
            Value value = redisRead(kind, key);
            if (value != null && value.expiresAt() > System.currentTimeMillis()) { count(kind, "l2"); return value; }
            count(kind, "db");
            int ttl = properties.redisSeconds() + ThreadLocalRandom.current().nextInt(Math.max(1, properties.redisSeconds() / 10));
            try {
                T loaded = loader.get();
                if ((loaded instanceof java.util.Collection<?> collection && collection.isEmpty())
                        || (loaded instanceof com.ticketing.common.PageResponse<?> page && page.items().isEmpty())) ttl = properties.negativeSeconds();
                value = new Value(json.writeValueAsString(loaded), null, null, System.currentTimeMillis() + ttl * 1000L);
            } catch (BusinessException exception) {
                if (exception.getStatus() != HttpStatus.NOT_FOUND) throw exception;
                ttl = properties.negativeSeconds();
                value = new Value(null, exception.getCode(), exception.getMessage(), System.currentTimeMillis() + ttl * 1000L);
            }
            redisWrite(kind, key, value, ttl);
            return value;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "CATALOG_BUSY", "查询暂时中断，请稍后重试");
        } catch (JsonProcessingException exception) {
            count(kind, "load-failure");
            throw new IllegalStateException("公开目录序列化失败", exception);
        } catch (RuntimeException exception) {
            count(kind, "load-failure");
            throw exception;
        } finally { if (acquired) loads.release(); }
    }

    private Value redisRead(Kind kind, String key) {
        if (System.nanoTime() < redisRetryAt.get()) return null;
        long start = System.nanoTime();
        try {
            String data = redis.opsForValue().get(key);
            return data == null ? null : json.readValue(data, Value.class);
        } catch (DataAccessException | JsonProcessingException exception) { redisFailure(kind); return null; }
        finally { meters.timer("ticketing.catalog.redis.duration", "operation", "read").record(System.nanoTime() - start, TimeUnit.NANOSECONDS); }
    }

    private void redisWrite(Kind kind, String key, Value value, int ttl) throws JsonProcessingException {
        if (System.nanoTime() < redisRetryAt.get()) return;
        String data = json.writeValueAsString(value);
        long start = System.nanoTime();
        try { redis.opsForValue().set(key, data, Duration.ofSeconds(ttl)); }
        catch (DataAccessException exception) { redisFailure(kind); }
        finally { meters.timer("ticketing.catalog.redis.duration", "operation", "write").record(System.nanoTime() - start, TimeUnit.NANOSECONDS); }
    }

    private void redisFailure(Kind kind) {
        redisRetryAt.set(System.nanoTime() + TimeUnit.SECONDS.toNanos(1));
        count(kind, "redis-unavailable");
    }
    private long localNanos(Value value) {
        return TimeUnit.MILLISECONDS.toNanos(Math.max(0, Math.min(properties.localSeconds() * 1000L, value.expiresAt() - System.currentTimeMillis())));
    }
    private void count(Kind kind, String outcome) { meters.counter("ticketing.catalog.requests", "kind", kind.name().toLowerCase(java.util.Locale.ROOT), "outcome", outcome).increment(); }
    public enum Kind { DETAIL, SESSIONS, LIST }
    private record Value(String payload, String code, String message, long expiresAt) { }
}
