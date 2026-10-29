package com.ticketing.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ticketing.config.PurchaseProperties;
import com.ticketing.common.RedisCommandDiagnostics;
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
import java.util.HashMap;
import org.springframework.core.io.ClassPathResource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.function.Supplier;

/** 只有一个预扣账本：入口原子预扣，数据库终态提交之后原子结算。没有 SQL 依赖。 */
@Service
public class RedisStockReservationService {
    public static final String PREFIX = "ticketing:seckill:v2:";
    private final String recovery;
    private final String pending;
    private final String prefix;
    private static final long RETENTION = Duration.ofDays(7).toSeconds();
    private static final String MAX_SESSION_ID = Long.toString(Long.MAX_VALUE);
    private static final DefaultRedisScript<List> RESERVE = script("purchase-reserve.lua", List.class);
    private static final DefaultRedisScript<Long> SETTLE = script("purchase-settle.lua", Long.class);
    private static final DefaultRedisScript<Long> PUBLISH = script("inventory-publish.lua", Long.class);
    private static final DefaultRedisScript<Long> REMEMBER = script("purchase-remember.lua", Long.class);
    private static final DefaultRedisScript<Long> DEFER = new DefaultRedisScript<>("""
            local score = redis.call('ZSCORE', KEYS[1], ARGV[2])
            local now = tonumber(ARGV[1])
            if not score or tonumber(score) > now then return 0 end
            local base = tonumber(ARGV[3])
            local accepted = tonumber(redis.call('HGET', KEYS[2], 'acceptedAt')) or now
            local deadline = tonumber(redis.call('HGET', KEYS[2], 'deadlineAt')) or 0
            local delay = math.min(tonumber(ARGV[4]), base * (1 + math.floor(math.max(0, now - accepted) / base)))
            local next = now + delay
            if deadline > now then next = math.min(next, deadline) end
            redis.call('ZADD', KEYS[1], 'XX', next, ARGV[2])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> PRIORITY = script("waitlist-priority.lua", Long.class);
    private static final DefaultRedisScript<List> ADMISSION_SNAPSHOT = new DefaultRedisScript<>(
            "return {redis.call('HGET',KEYS[1],'ready') or '',tostring(redis.call('EXISTS',KEYS[2])),"
                    + "redis.call('GET',KEYS[2]) or '',redis.call('HGET',KEYS[1],'waitlistPriority') or '',"
                    + "tostring(redis.call('HGET',KEYS[1],'waitlistRequest') ~= false and redis.call('HGET',KEYS[1],'waitlistRequest') ~= '')}", List.class);
    private final StringRedisTemplate redis;
    private final PurchaseProperties properties;
    private final int maxPerSession;
    private final RedisCommandDiagnostics diagnostics;

    @Autowired
    public RedisStockReservationService(StringRedisTemplate redis, PurchaseProperties properties,
            @Value("${ticketing.order.max-per-user-session:4}") int maxPerSession,
            ObjectProvider<RedisCommandDiagnostics> diagnostics) {
        this(redis, properties, maxPerSession, diagnostics.getIfAvailable());
    }

    public RedisStockReservationService(StringRedisTemplate redis, PurchaseProperties properties,
            @Value("${ticketing.order.max-per-user-session:4}") int maxPerSession) {
        this(redis, properties, maxPerSession, (RedisCommandDiagnostics) null);
    }

    private RedisStockReservationService(StringRedisTemplate redis, PurchaseProperties properties,
            int maxPerSession, RedisCommandDiagnostics diagnostics) {
        this.redis = redis; this.properties = properties; this.maxPerSession = maxPerSession;
        this.diagnostics = diagnostics;
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
            String session = observe("purchase.reserve.readSession", Map.of(),
                    () -> text(redis.opsForHash().get(skuKey(skuId), "sessionId")));
            long candidate = intent.requestId() == null ? IdWorker.getId() : intent.requestId();
            long luaStarted = diagnostics == null ? 0 : diagnostics.start();
            Map<String, Object> luaFacts = Map.of("requestIdKnown", true, "reserveOutcomeKnown", false);
            List<?> result;
            int code;
            try {
                result = redis.execute(RESERVE,
                        List.of(skuKey(skuId), idemKey(userId, operation, key), requestKey(candidate), recovery, pending,
                                userKey(userId), quotaKey(userId, session)),
                        Long.toString(candidate), Long.toString(userId), Long.toString(skuId), Integer.toString(quantity),
                        key, HexFormat.of().formatHex(hash(skuId + ":" + quantity)), Integer.toString(properties.capacity()),
                        Integer.toString(properties.queueSeconds()), Integer.toString(maxPerSession), session,
                        Integer.toString(properties.recoverySeconds()), operation,
                        intent.waitlistEntryId() == null ? "" : intent.waitlistEntryId().toString(),
                        intent.waitlistEntryId() == null ? "" : intent.waitlistEntryId() + ":" + intent.attemptNo(),
                        intent.expiresAt() == null ? "0" : Long.toString(epoch(intent.expiresAt())));
                if (result == null || result.isEmpty() || !(result.getFirst() instanceof Number number)) {
                    throw new IllegalStateException("预扣脚本返回格式异常");
                }
                code = number.intValue();
            } catch (RuntimeException exception) {
                if (diagnostics != null) diagnostics.failure("purchase.reserve.lua", exception, luaStarted, luaFacts);
                throw exception;
            }
            Map<String, Object> branchFacts = new HashMap<>();
            branchFacts.put("requestIdKnown", true);
            branchFacts.put("reserveOutcomeKnown", true);
            branchFacts.put("reserveBranch", Integer.toString(code));
            if (code == -3 && diagnostics != null) branchFacts.putAll(minusThreeSnapshot(skuId));
            if (diagnostics != null) diagnostics.branch("purchase.reserve.lua", code, luaStarted, branchFacts);
            if (code == -12) throw failure(HttpStatus.CONFLICT, "WAITLIST_PRIORITY", "该票档正在优先处理候补，请加入候补队列");
            if (code == -13) throw failure(HttpStatus.CONFLICT, "WAITLIST_ATTEMPT_STALE", "候补分配状态已变化");
            if (code == -1) throw failure(HttpStatus.CONFLICT, "CREATE_ORDER".equals(operation) ? "TICKET_UNAVAILABLE" : "STOCK_UNAVAILABLE", "票档当前可预扣库存不足");
            if (code == -6) throw failure(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "同一幂等键不能用于不同票档或数量");
            if (code == -11) throw failure(HttpStatus.CONFLICT, "PURCHASE_LIMIT", "购买数量超过票档或本场入口限额");
            if (code == -10) throw failure(HttpStatus.CONFLICT, "TICKET_UNAVAILABLE", "票档当前不可售或库存不足");
            if (code == -2) throw failure(HttpStatus.SERVICE_UNAVAILABLE, "PURCHASE_QUEUE_FULL", "购票排队人数已达上限，请稍后重试");
            if (code != 1 && code != 2) throw unavailable();
            Map<String, Object> readbackFacts = new HashMap<>();
            readbackFacts.put("requestIdKnown", true);
            readbackFacts.put("reserveOutcomeKnown", true);
            readbackFacts.put("reserveBranch", Integer.toString(code));
            PurchaseRequest request = observe("purchase.reserve.lookup", readbackFacts,
                    () -> lookupRaw(userId, operation, key));
            if (request == null) throw unavailable();
            readbackFacts.put("requestIdKnown", true);
            if (!request.terminal() && observe("purchase.reserve.find", readbackFacts,
                    () -> findRaw(request.id())) == null) {
                pause(skuId, "missing_request_after_reserve");
                throw unavailable();
            }
            return request;
        } catch (BusinessException exception) { throw exception; }
        catch (RuntimeException exception) { throw unavailable(); }
    }

    public PurchaseRequest find(long id) {
        return observe("purchase.reserve.find", Map.of(), () -> findRaw(id));
    }

    private PurchaseRequest findRaw(long id) { return decode(redis.opsForHash().entries(requestKey(id))); }

    public PurchaseRequest lookup(long userId, String key) {
        return lookup(userId, "CREATE_ORDER", key);
    }

    public PurchaseRequest lookup(long userId, String operation, String key) {
        return observe("purchase.reserve.lookup", Map.of(), () -> lookupRaw(userId, operation, key));
    }

    private PurchaseRequest lookupRaw(long userId, String operation, String key) {
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
            pause(skuId, "required_request_or_sku_missing");
            throw unavailable();
        }
        return request;
    }

    public boolean settle(PurchaseRequest result) {
        if (!result.terminal()) throw new IllegalArgumentException("只有数据库终态可以结算预扣");
        long started = diagnostics == null ? 0 : diagnostics.start();
        String outcome = "not_settled";
        RuntimeException failure = null;
        try {
            // 正常路径只读sessionId并执行结算Lua，不再先查询EXISTS。
            String session = text(redis.opsForHash().get(requestKey(result.id()), "sessionId"));
            if (session.isEmpty()) {
                // HGETALL同时区分详情已过期与字段缺失；remember回填的终态详情本就没有sessionId。
                Map<Object, Object> fields = redis.opsForHash().entries(requestKey(result.id()));
                boolean missing = fields.isEmpty();
                String expectedState = "SUCCEEDED".equals(result.status()) ? "CONSUMED" : "RELEASED";
                String expectedOrder = result.orderId() == null ? "" : result.orderId().toString();
                boolean terminalMatches = expectedState.equals(text(fields.get("state")))
                        && Long.toString(result.id()).equals(text(fields.get("id")))
                        && result.status().equals(text(fields.get("status")))
                        && expectedOrder.equals(text(fields.get("orderId")));
                if (missing || terminalMatches) {
                    PurchaseRequest summary = lookup(result.userId(), result.idempotencyOperation(), result.idempotencyKey());
                    if (summary != null && summary.id() == result.id() && summary.status().equals(result.status())
                            && java.util.Objects.equals(summary.orderId(), result.orderId())) {
                        outcome = "already_settled_fact_matches";
                        return true;
                    }
                }
                pause(result.skuId(), missing ? "settle_request_missing" : "settle_session_missing_or_conflicting");
                outcome = missing ? "request_missing_paused" : "session_missing_or_conflicting_paused";
                return false;
            }
            if (!validSessionId(session)) {
                pause(result.skuId(), "settle_session_invalid");
                outcome = "session_invalid_paused";
                return false;
            }
            Long value = redis.execute(SETTLE,
                    List.of(skuKey(result.skuId()), requestKey(result.id()), idemKey(result.userId(), result.idempotencyOperation(), result.idempotencyKey()),
                            recovery, pending, userKey(result.userId()), quotaKey(result.userId(), session)),
                    Long.toString(result.id()), result.status(), result.orderId() == null ? "" : result.orderId().toString(),
                    result.failureCode() == null ? "" : result.failureCode(), Long.toString(RETENTION),
                    result.skuId() + ":" + result.id());
            if (value != null && (value == 0 || value == 1)) { outcome = "settled"; return true; }
            pause(result.skuId(), "settle_script_rejected");
            outcome = "script_rejected_paused";
            return false;
        } catch (RuntimeException exception) { failure = exception; return false; }
        finally {
            if (diagnostics != null) {
                if (failure == null) diagnostics.success("purchase.settle", outcome, started, Map.of(), true);
                else diagnostics.failure("purchase.settle", failure, started, Map.of());
            }
        }
    }

    /** 只接受现有发布协议写出的规范正long场次ID，拒绝空值、符号、前导零和溢出。 */
    private static boolean validSessionId(String session) {
        if (session.isEmpty() || session.length() > MAX_SESSION_ID.length()
                || session.charAt(0) < '1' || session.charAt(0) > '9') return false;
        for (int index = 1; index < session.length(); index++) {
            char digit = session.charAt(index);
            if (digit < '0' || digit > '9') return false;
        }
        return session.length() < MAX_SESSION_ID.length() || session.compareTo(MAX_SESSION_ID) <= 0;
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
        return pause(skuId, "explicit_pause");
    }

    private long pause(long skuId, String reason) {
        if (diagnostics == null) {
            Long result = redis.execute(PUBLISH, List.of(skuKey(skuId), pending), "pause");
            return result == null ? -1 : result;
        }
        long started = diagnostics.start();
        Map<String, Object> facts = Map.of("reason", reason);
        try {
            Long result = redis.execute(PUBLISH, List.of(skuKey(skuId), pending), "pause");
            long pendingCount = result == null ? -1 : result;
            Map<String, Object> detail = new HashMap<>(facts);
            detail.put("pendingCount", pendingCount);
            diagnostics.success("purchase.reserve.pause", pendingCount < 0 ? "unknown" : "paused", started, detail, true);
            return pendingCount;
        } catch (RuntimeException exception) {
            diagnostics.failure("purchase.reserve.pause", exception, started, facts);
            throw exception;
        }
    }

    public void publish(long skuId, long available, boolean eligible, long starts, long ends, int maxQuantity,
            long sessionId, long sequence, boolean initialize, boolean initializeCounter, boolean priority) {
        Long result = redis.execute(PUBLISH, List.of(skuKey(skuId), pending), initialize ? "initialize" : "publish",
                Long.toString(available), eligible ? "1" : "0", Long.toString(starts), Long.toString(ends),
                Integer.toString(maxQuantity), Long.toString(sessionId), Long.toString(sequence), initializeCounter ? "1" : "0", priority ? "1" : "0");
        if (result == null || result != 1) throw unavailable();
    }

    public boolean needsInventoryReconciliation(long skuId, long version) {
        Map<Object, Object> fields = redis.opsForHash().entries(skuKey(skuId));
        if (fields.isEmpty()) return true;
        String saved = text(fields.get("inventoryVersion"));
        if (saved.isEmpty()) saved = text(fields.get("inventorySequence"));
        try {
            long current = Long.parseLong(saved);
            long available = Long.parseLong(text(fields.get("available")));
            long pendingCount = Long.parseLong(text(fields.get("pending")));
            long waitlistPending = Long.parseLong(text(fields.get("waitlistPending")));
            String priority = text(fields.get("waitlistPriority"));
            String eligible = text(fields.get("eligible"));
            if (current < 0 || available < 0 || pendingCount < 0
                    || waitlistPending < 0 || waitlistPending > pendingCount
                    || !("0".equals(priority) || "1".equals(priority))
                    || !("0".equals(eligible) || "1".equals(eligible))
                    || !fields.keySet().containsAll(List.of("eligible", "saleStartsAt", "saleEndsAt",
                            "maxQuantity", "sessionId", "waitlistPending", "waitlistPriority"))) {
                throw new IllegalStateException("库存账本字段异常");
            }
            return !"1".equals(text(fields.get("ready"))) || current != version;
        } catch (NumberFormatException | IllegalStateException invalid) {
            pause(skuId, "inventory_ledger_invalid");
            return true;
        }
    }

    /** 有在途预扣时 Redis 与数据库余额本就可能暂时不同，等待结算后再核对。 */
    public boolean inventoryBalanceDiffers(long skuId, long version, long expected) {
        Map<Object, Object> fields = redis.opsForHash().entries(skuKey(skuId));
        String saved = text(fields.get("inventoryVersion"));
        if (saved.isEmpty()) saved = text(fields.get("inventorySequence"));
        try {
            return !"1".equals(text(fields.get("ready"))) || Long.parseLong(saved) != version
                    || (Long.parseLong(text(fields.get("pending"))) == 0
                        && Long.parseLong(text(fields.get("available"))) != expected);
        } catch (NumberFormatException invalid) {
            pause(skuId, "inventory_ledger_invalid");
            return true;
        }
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

    public boolean defer(String member, long requestId) {
        long base = properties.recoverySeconds() * 1000L;
        long maximum = Math.max(base, properties.queueSeconds() * 1000L / 3);
        Long deferred = redis.execute(DEFER, List.of(recovery, requestKey(requestId)),
                Long.toString(System.currentTimeMillis()), member, Long.toString(base), Long.toString(maximum));
        return deferred != null && deferred == 1;
    }

    public String skuKey(long id) { return prefix + "sku:" + id; }
    public String requestKey(long id) { return prefix + "request:" + id; }
    private String userKey(long id) { return prefix + "user:" + id; }
    private String quotaKey(long user, String session) { return prefix + "quota:" + session + ":" + user; }
    private String idemKey(long user, String operation, String key) {
        return prefix + "idem:" + user + ":" + operation + ":" + HexFormat.of().formatHex(hash(key));
    }

    private <T> T observe(String stage, Map<String, ?> facts, Supplier<T> call) {
        if (diagnostics == null) return call.get();
        long started = diagnostics.start();
        try {
            T result = call.get();
            diagnostics.success(stage, result == null ? "missing" : "success", started, facts, result == null);
            return result;
        } catch (RuntimeException exception) {
            diagnostics.failure(stage, exception, started, facts);
            throw exception;
        }
    }

    /** Lua -3 已返回后才做一次只读快照；普通成功热路径不增加 Redis 往返。 */
    private Map<String, Object> minusThreeSnapshot(long skuId) {
        long started = diagnostics.start();
        try {
            List<?> value = redis.execute(ADMISSION_SNAPSHOT, List.of(skuKey(skuId), pending));
            if (value == null || value.size() != 5) throw new IllegalStateException("准入快照返回格式异常");
            String ready = text(value.get(0));
            boolean pendingKeyExists = "1".equals(text(value.get(1)));
            String pendingValue = text(value.get(2));
            String priority = text(value.get(3));
            boolean waitlistRequestPresent = Boolean.parseBoolean(text(value.get(4)));
            String reason = !"1".equals(ready) ? "ready_not_1"
                    : !pendingKeyExists ? "pending_key_missing"
                    : priority.isEmpty() ? "waitlist_priority_missing"
                    : waitlistRequestPresent ? "waitlist_request_present" : "other_minus_3";
            Map<String, Object> facts = new HashMap<>();
            facts.put("ready", ready);
            facts.put("pendingKeyExists", pendingKeyExists);
            facts.put("pending", pendingValue);
            facts.put("waitlistPriority", priority);
            facts.put("waitlistRequestPresent", waitlistRequestPresent);
            facts.put("reason", reason);
            diagnostics.success("purchase.reserve.lua.minus3Snapshot", "captured", started, facts, true);
            return facts;
        } catch (RuntimeException exception) {
            diagnostics.failure("purchase.reserve.lua.minus3Snapshot", exception, started, Map.of());
            return Map.of("reason", "snapshot_unavailable");
        }
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
