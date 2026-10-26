package com.ticketing.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ticketing.config.PurchaseProperties;
import com.ticketing.entity.PurchaseRequest;
import com.ticketing.dto.PurchaseIntent;
import com.ticketing.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 只有一个预扣账本：入口原子预扣，数据库终态提交之后原子结算。没有 SQL 依赖。 */
@Service
public class RedisStockReservationService {
    public static final String PREFIX = "ticketing:seckill:v2:";
    private final String recovery;
    private final String pending;
    private final String prefix;
    private static final long RETENTION = Duration.ofDays(7).toSeconds();
    private static final DefaultRedisScript<List> RESERVE = script("purchase-reserve.lua", List.class);
    private static final DefaultRedisScript<Long> SETTLE = script("purchase-settle.lua", Long.class);
    private static final DefaultRedisScript<Long> PUBLISH = script("inventory-publish.lua", Long.class);
    private static final DefaultRedisScript<Long> REMEMBER = script("purchase-remember.lua", Long.class);
    private static final DefaultRedisScript<Long> DEFER = new DefaultRedisScript<>(
            "return redis.call('ZADD',KEYS[1],'XX',ARGV[1],ARGV[2])", Long.class);
    private static final DefaultRedisScript<Long> PRIORITY = script("waitlist-priority.lua", Long.class);
    private final StringRedisTemplate redis;
    private final PurchaseProperties properties;
    private final int maxPerSession;

    public RedisStockReservationService(StringRedisTemplate redis, PurchaseProperties properties,
            @Value("${ticketing.order.max-per-user-session:4}") int maxPerSession) {
        this.redis = redis; this.properties = properties; this.maxPerSession = maxPerSession;
        this.prefix = PREFIX + properties.namespace() + ":"; this.recovery = prefix + "recovery"; this.pending = prefix + "pending";
    }

    public PurchaseRequest reserve(long userId, long skuId, int quantity, String key) {
        return reserve(new PurchaseIntent(userId, skuId, quantity, "CREATE_ORDER", key, null, 0, null, null));
    }

    public PurchaseRequest reserve(PurchaseIntent intent) {
        long userId = intent.userId(), skuId = intent.skuId();
        int quantity = intent.quantity();
        String key = intent.key(), operation = intent.operation();
        if (!("CREATE_ORDER".equals(operation) && intent.waitlistEntryId() == null)
                && !("WAITLIST_CONFIRM".equals(operation) && intent.waitlistEntryId() != null)) {
            throw new IllegalArgumentException("购票来源不一致");
        }
        try {
            String session = text(redis.opsForHash().get(skuKey(skuId), "sessionId"));
            long candidate = intent.requestId() == null ? IdWorker.getId() : intent.requestId();
            List<?> result = redis.execute(RESERVE,
                    List.of(skuKey(skuId), idemKey(userId, operation, key), requestKey(candidate), recovery, pending,
                            userKey(userId), quotaKey(userId, session)),
                    Long.toString(candidate), Long.toString(userId), Long.toString(skuId), Integer.toString(quantity),
                    key, HexFormat.of().formatHex(hash(skuId + ":" + quantity)), Integer.toString(properties.capacity()),
                    Integer.toString(properties.queueSeconds()), Integer.toString(maxPerSession), session,
                    Integer.toString(properties.recoverySeconds()), operation,
                    intent.waitlistEntryId() == null ? "" : intent.waitlistEntryId().toString(),
                    intent.waitlistEntryId() == null ? "" : intent.waitlistEntryId() + ":" + intent.attemptNo(),
                    intent.expiresAt() == null ? "0" : Long.toString(epoch(intent.expiresAt())));
            if (result == null || result.isEmpty()) throw unavailable();
            int code = ((Number) result.getFirst()).intValue();
            if (code == -12) throw failure(HttpStatus.CONFLICT, "WAITLIST_PRIORITY", "该票档正在优先处理候补，请加入候补队列");
            if (code == -13) throw failure(HttpStatus.CONFLICT, "WAITLIST_ATTEMPT_STALE", "候补分配状态已变化");
            if (code == -1) throw failure(HttpStatus.CONFLICT, "CREATE_ORDER".equals(operation) ? "TICKET_UNAVAILABLE" : "STOCK_UNAVAILABLE", "票档当前可预扣库存不足");
            if (code == -6) throw failure(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "同一幂等键不能用于不同票档或数量");
            if (code == -11) throw failure(HttpStatus.CONFLICT, "PURCHASE_LIMIT", "购买数量超过票档或本场入口限额");
            if (code == -10) throw failure(HttpStatus.CONFLICT, "TICKET_UNAVAILABLE", "票档当前不可售或库存不足");
            if (code == -2) throw failure(HttpStatus.SERVICE_UNAVAILABLE, "PURCHASE_QUEUE_FULL", "购票排队人数已达上限，请稍后重试");
            if (code != 1 && code != 2) throw unavailable();
            PurchaseRequest request = lookup(userId, operation, key);
            if (request == null) throw unavailable();
            if (!request.terminal() && find(request.id()) == null) {
                pause(skuId);
                throw unavailable();
            }
            return request;
        } catch (BusinessException exception) { throw exception; }
        catch (RuntimeException exception) { throw unavailable(); }
    }

    public PurchaseRequest find(long id) {
        return decode(redis.opsForHash().entries(requestKey(id)));
    }

    public PurchaseRequest lookup(long userId, String key) {
        return lookup(userId, "CREATE_ORDER", key);
    }

    public PurchaseRequest lookup(long userId, String operation, String key) {
        return decode(redis.opsForHash().entries(idemKey(userId, operation, key)));
    }

    public List<PurchaseRequest> active(long userId) {
        Set<String> ids = redis.opsForZSet().reverseRange(userKey(userId), 0, 49);
        if (ids == null) return List.of();
        return ids.stream().map(id -> find(Long.parseLong(id))).filter(r -> r != null && !r.terminal()).toList();
    }

    /** 不能从丢失凭据猜测参数或返票；保留索引并停止对应 SKU 新受理。 */
    public PurchaseRequest require(long id, long skuId) {
        PurchaseRequest request = find(id);
        if (request == null || request.skuId() != skuId || !Boolean.TRUE.equals(redis.hasKey(skuKey(skuId)))) {
            pause(skuId);
            throw unavailable();
        }
        return request;
    }

    public boolean settle(PurchaseRequest result) {
        if (!result.terminal()) throw new IllegalArgumentException("只有数据库终态可以结算预扣");
        try {
            if (!Boolean.TRUE.equals(redis.hasKey(requestKey(result.id())))) {
                PurchaseRequest summary = lookup(result.userId(), result.idempotencyOperation(), result.idempotencyKey());
                if (summary != null && summary.id() == result.id() && summary.status().equals(result.status())
                        && java.util.Objects.equals(summary.orderId(), result.orderId())) return true;
                pause(result.skuId());
                return false;
            }
            String session = text(redis.opsForHash().get(requestKey(result.id()), "sessionId"));
            Long value = redis.execute(SETTLE,
                    List.of(skuKey(result.skuId()), requestKey(result.id()), idemKey(result.userId(), result.idempotencyOperation(), result.idempotencyKey()),
                            recovery, pending, userKey(result.userId()), quotaKey(result.userId(), session)),
                    Long.toString(result.id()), result.status(), result.orderId() == null ? "" : result.orderId().toString(),
                    result.failureCode() == null ? "" : result.failureCode(), Long.toString(RETENTION),
                    result.skuId() + ":" + result.id());
            if (value != null && value >= 0) return true;
            pause(result.skuId());
            return false;
        } catch (RuntimeException exception) { return false; }
    }

    /** 历史终态预装及只读查询回填；绝不覆盖还需要结算的 RESERVED 凭据。 */
    public void remember(PurchaseRequest result) {
        if (!result.terminal()) return;
        redis.execute(REMEMBER, List.of(idemKey(result.userId(), result.idempotencyOperation(), result.idempotencyKey()), requestKey(result.id())),
                Long.toString(result.id()), Long.toString(result.userId()), Long.toString(result.skuId()),
                Integer.toString(result.quantity()), result.idempotencyKey(), HexFormat.of().formatHex(result.requestHash()),
                Long.toString(epoch(result.acceptedAt())), Long.toString(epoch(result.deadlineAt())), result.status(),
                result.orderId() == null ? "" : result.orderId().toString(), result.failureCode() == null ? "" : result.failureCode(),
                Long.toString(RETENTION), result.idempotencyOperation(),
                result.waitlistEntryId() == null ? "" : result.waitlistEntryId().toString());
    }

    /** 暂停可以独立调用；发布绝对余额必须持 MySQL SKU 锁，不使用冻结租约。 */
    public long pause(long skuId) {
        Long result = redis.execute(PUBLISH, List.of(skuKey(skuId), pending), "pause");
        return result == null ? -1 : result;
    }

    public void publish(long skuId, long available, boolean eligible, long starts, long ends, int maxQuantity,
            long sessionId, long sequence, boolean initialize, boolean initializeCounter, boolean priority) {
        Long result = redis.execute(PUBLISH, List.of(skuKey(skuId), pending), initialize ? "initialize" : "publish",
                Long.toString(available), eligible ? "1" : "0", Long.toString(starts), Long.toString(ends),
                Integer.toString(maxQuantity), Long.toString(sessionId), Long.toString(sequence), initializeCounter ? "1" : "0", priority ? "1" : "0");
        if (result == null || result != 1) throw unavailable();
    }

    public boolean needsInventoryReconciliation(long skuId, long sequence) {
        Map<Object, Object> fields = redis.opsForHash().entries(skuKey(skuId));
        return !"1".equals(text(fields.get("ready")))
                || Long.parseLong(text(fields.getOrDefault("inventorySequence", "0"))) < sequence;
    }

    /** 调用者持 MySQL SKU 锁；同一原子脚本检查余额并开启门禁。 */
    public void joinPriority(long skuId, int quantity, boolean queued) {
        long result = priority(skuId, "join", Integer.toString(quantity), queued ? "1" : "0");
        if (result == -2) throw failure(HttpStatus.CONFLICT, "WAITLIST_CONFLICT", "当前库存充足，请直接购买");
        if (result != 1) throw unavailable();
    }

    public void selectWaitlist(long skuId, long entryId, int attempt) {
        if (priority(skuId, "select", entryId + ":" + attempt, "") != 1) throw unavailable();
    }

    public boolean finishWaitlistAttempt(long skuId, long entryId, int attempt) {
        return priority(skuId, "finish", entryId + ":" + attempt, "") == 1;
    }

    public boolean closePriority(long skuId) { return priority(skuId, "close", "", "") == 1; }

    private long priority(long skuId, String action, String value, String queued) {
        Long result = redis.execute(PRIORITY, List.of(skuKey(skuId)), action, value, queued);
        return result == null ? -1 : result;
    }

    public String recoveryKey() { return recovery; }

    public Set<String> due(int size) {
        Set<String> result = redis.opsForZSet().rangeByScore(recovery, 0, System.currentTimeMillis(), 0, size);
        return result == null ? Set.of() : result;
    }

    public void defer(String member) {
        redis.execute(DEFER, List.of(recovery),
                Long.toString(System.currentTimeMillis() + properties.recoverySeconds() * 1000L), member);
    }

    public String skuKey(long id) { return prefix + "sku:" + id; }
    public String requestKey(long id) { return prefix + "request:" + id; }
    private String userKey(long id) { return prefix + "user:" + id; }
    private String quotaKey(long user, String session) { return prefix + "quota:" + session + ":" + user; }
    private String idemKey(long user, String operation, String key) {
        return prefix + "idem:" + user + ":" + operation + ":" + HexFormat.of().formatHex(hash(key));
    }
    private static byte[] hash(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("运行环境缺少 SHA-256", exception); }
    }
    private static long epoch(LocalDateTime time) { return time.toInstant(ZoneOffset.UTC).toEpochMilli(); }
    private static String text(Object value) { return value == null ? "" : value.toString(); }
    private static PurchaseRequest decode(Map<Object, Object> fields) {
        if (fields.isEmpty()) return null;
        String order = text(fields.get("orderId"));
        String failure = text(fields.get("failureCode"));
        LocalDateTime accepted = LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(text(fields.get("acceptedAt")))), ZoneOffset.UTC);
        LocalDateTime deadline = LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(text(fields.get("deadlineAt")))), ZoneOffset.UTC);
        return new PurchaseRequest(Long.parseLong(text(fields.get("id"))), Long.parseLong(text(fields.get("userId"))),
                Long.parseLong(text(fields.get("skuId"))), Integer.parseInt(text(fields.get("quantity"))),
                text(fields.get("idempotencyKey")), HexFormat.of().parseHex(text(fields.get("requestHash"))),
                text(fields.get("status")), order.isEmpty() ? null : Long.parseLong(order), failure.isEmpty() ? null : failure,
                accepted, deadline, accepted, text(fields.get("operation")),
                text(fields.get("waitlistEntryId")).isEmpty() ? null : Long.parseLong(text(fields.get("waitlistEntryId"))));
    }
    private static BusinessException failure(HttpStatus status, String code, String message) {
        return new BusinessException(status, code, message);
    }
    private static BusinessException unavailable() {
        return failure(HttpStatus.SERVICE_UNAVAILABLE, "PURCHASE_ADMISSION_UNAVAILABLE", "购票入口暂不可用，请使用原申请重试或查询");
    }
    private static <T> DefaultRedisScript<T> script(String name, Class<T> type) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("seckill/" + name)); script.setResultType(type); return script;
    }
}
