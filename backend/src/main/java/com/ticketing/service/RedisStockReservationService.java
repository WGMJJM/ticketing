package com.ticketing.service;

import com.ticketing.config.PurchaseProperties;
import com.ticketing.dto.StockReservation;
import com.ticketing.entity.PurchaseRequest;
import com.ticketing.exception.BusinessException;
import com.ticketing.mapper.PurchaseRequestMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntUnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Redis库存预扣账本；MySQL仍是订单和最终库存的唯一权威。 */
@Service
public class RedisStockReservationService {
    private static final Logger LOG = LoggerFactory.getLogger(RedisStockReservationService.class);
    private static final String PREFIX = "ticketing:seckill:v1:";
    private static final String RECOVERY = PREFIX + "reservation-recovery";
    private static final String PENDING = PREFIX + "pending-count";
    private static final String CAPACITY_INIT_KEY = PREFIX + "capacity-init";
    private static final String FROZEN = PREFIX + "frozen-skus";
    private static final String FENCE = PREFIX + "freeze-fence";
    private static final long FREEZE_LEASE_MS = 300_000;
    private static final long INIT_LEASE_MS = 5_000;
    private static final long RECOVERY_LEASE_MS = 120_000;
    private static final long ORPHAN_GRACE_MS = 60_000;
    private static final int TOKEN_SCAN_LIMIT = 20_000;
    private static final int FREEZE_RECONCILE_ATTEMPTS = 5;
    private static final long TERMINAL_RETENTION_SECONDS = Duration.ofDays(7).toSeconds();

    private static final DefaultRedisScript<List> RESERVE = listScript("seckill/stock-reserve-v1.lua");
    private static final DefaultRedisScript<List> FREEZE = listScript("seckill/stock-freeze-v1.lua");
    private static final DefaultRedisScript<Long> CLAIM_RECOVERY = longScript("seckill/stock-recovery-claim-v1.lua");
    private static final DefaultRedisScript<List> SETTLE = listScript("seckill/stock-settle-v1.lua");
    private static final DefaultRedisScript<Long> SNAPSHOT = longScript("seckill/stock-snapshot-v1.lua");
    private static final DefaultRedisScript<Long> CAPACITY_INIT = longScript("seckill/stock-capacity-init-v1.lua");
    private static final DefaultRedisScript<Long> CAPACITY_PUBLISH = longScript("seckill/stock-capacity-publish-v1.lua");
    private static final DefaultRedisScript<Long> BIND = longScript("seckill/stock-bind-v1.lua");
    private static final DefaultRedisScript<Long> DEFER = longScript("seckill/stock-recovery-defer-v1.lua");
    private static final DefaultRedisScript<Long> RESTORE = longScript("seckill/stock-restore-v1.lua");

    private final StringRedisTemplate redis;
    private final PurchaseRequestMapper requests;
    private final PurchaseAdmissionTransaction admission;
    private final StockSnapshotService snapshots;
    private final PurchaseProperties properties;
    private final MeterRegistry meters;

    public RedisStockReservationService(StringRedisTemplate redis, PurchaseRequestMapper requests,
            PurchaseAdmissionTransaction admission, StockSnapshotService snapshots,
            PurchaseProperties properties, MeterRegistry meters) {
        this.redis = redis;
        this.requests = requests;
        this.admission = admission;
        this.snapshots = snapshots;
        this.properties = properties;
        this.meters = meters;
    }

    /** 只允许在HTTP受理事务外运行；Redis失败时绝不退化成MySQL直售。 */
    public void reserve(StockReservation reservation) {
        requireOutsideTransaction();
        try {
            ensurePendingCounter();
            for (int attempt = 0; attempt < 2; attempt++) {
                List<?> result = redis.execute(RESERVE,
                        List.of(skuKey(reservation.skuId()), tokenKey(reservation.token()), skuTokensKey(reservation.skuId()),
                                RECOVERY, PENDING),
                        tokenHex(reservation.token()), Long.toString(reservation.userId()),
                        Long.toString(reservation.skuId()), Integer.toString(reservation.quantity()),
                        reservation.idempotencyKey(), HexFormat.of().formatHex(reservation.requestHash()),
                        Integer.toString(properties.capacity()), Long.toString(ORPHAN_GRACE_MS));
                long code = first(result);
                if (code == 1 || code == 2) {
                    meters.counter("ticketing.stock.reservation", "outcome", code == 1 ? "reserved" : "existing").increment();
                    return;
                }
                if (code == -3 || code == -9) {
                    synchronize(reservation.skuId());
                    continue;
                }
                if (code == -10) throw new BusinessException(HttpStatus.CONFLICT, "TICKET_UNAVAILABLE", "票档当前不在销售中，请刷新活动页面");
                if (code == -1) throw new BusinessException(HttpStatus.CONFLICT, "TICKET_UNAVAILABLE", "该票档当前库存不足，请刷新后重试");
                if (code == -2) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "PURCHASE_QUEUE_FULL",
                        "购票排队人数已达上限，请稍后重试", 2L);
                if (code == -8) {
                    PurchaseRequest current = requests.byReservationToken(reservation.token());
                    if (current != null && current.terminal()) return;
                    throw unavailable(null);
                }
                if (code == -6) throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "该购票申请已处理或幂等参数不一致");
                if (code == -7) throw unavailable(Math.max(1, (second(result) + 999) / 1000));
                throw unavailable(null);
            }
            throw unavailable(null);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            LOG.warn("Redis库存预扣失败，SKU {}，错误类型 {}", reservation.skuId(), exception.getClass().getSimpleName());
            meters.counter("ticketing.stock.reservation", "outcome", "unavailable").increment();
            throw unavailable(null);
        }
    }

    /** MySQL申请提交后建立Redis凭据与申请ID的反向关联；失败由恢复扫描补齐。 */
    public boolean bind(byte[] token, long requestId) {
        requireOutsideTransaction();
        try {
            long now = System.currentTimeMillis();
            Long result = redis.execute(BIND, List.of(tokenKey(token), RECOVERY),
                    tokenHex(token), Long.toString(requestId), Long.toString(now), Long.toString(ORPHAN_GRACE_MS));
            if (result == null || result != 1) {
                LOG.warn("购票申请已提交，但Redis预扣关联待恢复，请求 {}", requestId);
                return false;
            }
            return true;
        } catch (RuntimeException exception) {
            LOG.warn("购票申请已提交，但Redis预扣关联待恢复，请求 {}，错误类型 {}", requestId,
                    exception.getClass().getSimpleName());
            return false;
        }
    }

    /** Rabbit消费者在MySQL建单前确认Redis凭据仍完整；缺失时先冻结并按数据库活动申请恢复。 */
    public void requireBound(PurchaseRequest request) {
        requireOutsideTransaction();
        if (request.stockReservationToken() == null || request.stockReservationToken().length != 32) {
            throw unavailable(null);
        }
        ensurePendingCounter();
        Map<Object, Object> fields = redis.opsForHash().entries(tokenKey(request.stockReservationToken()));
        if (fields.isEmpty()) {
            synchronize(request.skuId());
            fields = redis.opsForHash().entries(tokenKey(request.stockReservationToken()));
        }
        if (!"RESERVED".equals(string(fields.get("state")))) throw unavailable(null);
        try {
            verify(request, request.stockReservationToken(), fields);
        } catch (IllegalStateException inconsistency) {
            acquireFreeze(request.skuId());
            throw inconsistency;
        }
        if (string(fields.get("requestId")).isBlank() && !bind(request.stockReservationToken(), request.id())) {
            throw unavailable(null);
        }
    }

    /** 建单终态已经提交后再结算Redis；调用失败由同一恢复扫描重试。 */
    public boolean settle(long skuId, byte[] token, String terminalStatus) {
        return settle(skuId, token, terminalStatus, "");
    }

    private boolean settle(long skuId, byte[] token, String terminalStatus, String recoveryOwner) {
        requireOutsideTransaction();
        String state = "SUCCEEDED".equals(terminalStatus) ? "CONSUMED" : "RELEASED";
        try {
            List<?> result = redis.execute(SETTLE,
                    List.of(skuKey(skuId), tokenKey(token), skuTokensKey(skuId), RECOVERY, PENDING),
                    tokenHex(token), state, Long.toString(TERMINAL_RETENTION_SECONDS), Long.toString(skuId), recoveryOwner);
            long code = first(result);
            if (code == -5) LOG.warn("旧预扣恢复认领已失效，SKU {}", skuId);
            else if (code < 0) {
                LOG.error("Redis预扣结算不一致，SKU {}，状态 {}，代码 {}", skuId, state, code);
                acquireFreeze(skuId);
            }
            return code >= 0;
        } catch (RuntimeException exception) {
            LOG.warn("Redis预扣终态待恢复，错误类型 {}", exception.getClass().getSimpleName());
            return false;
        }
    }

    /** Rabbit库存事件和周期补偿共用：冻结SKU、扣除已有预扣后再分配严格FIFO候补。 */
    public int dispatchFrozen(long skuId, IntUnaryOperator dispatch) {
        requireOutsideTransaction();
        ensurePendingCounter();
        FreezeLease lease = acquireFreeze(skuId);
        try {
            reconcileFrozen(skuId, lease, false);
            int allocated = dispatch.applyAsInt(Math.toIntExact(currentAvailable(skuId)));
            reconcileFrozen(skuId, lease, true);
            return allocated;
        } catch (RuntimeException exception) {
            meters.counter("ticketing.stock.freeze", "outcome", "left-frozen").increment();
            LOG.warn("库存仲裁未完成，SKU {} 保持冻结并由恢复扫描接管，错误类型 {}", skuId,
                    exception.getClass().getSimpleName());
            throw exception;
        }
    }

    /** 首次装载、版本刷新及停卖后的安全快照入口。 */
    public StockSnapshotService.InventorySnapshot synchronize(long skuId) {
        requireOutsideTransaction();
        ensurePendingCounter();
        FreezeLease lease = acquireFreeze(skuId);
        try {
            return reconcileFrozen(skuId, lease, true);
        } catch (RuntimeException exception) {
            meters.counter("ticketing.stock.freeze", "outcome", "left-frozen").increment();
            LOG.warn("Redis库存快照未完成，SKU {} 保持冻结，错误类型 {}", skuId, exception.getClass().getSimpleName());
            throw exception;
        }
    }

    /** 由既有购票恢复任务调用，处理数据库终态、Redis孤儿和过期冻结SKU。 */
    public void recoverBatch(int batchSize) {
        requireOutsideTransaction();
        try {
            ensurePendingCounter();
            long now = System.currentTimeMillis();
            Set<String> due = redis.opsForZSet().rangeByScore(RECOVERY, 0, now, 0, batchSize);
            if (due != null) {
                for (String token : due) {
                    try { recoverToken(token); }
                    catch (RuntimeException exception) {
                        LOG.warn("Redis预扣恢复暂未完成，错误类型 {}", exception.getClass().getSimpleName());
                    }
                }
            }
            Set<String> frozen = redis.opsForZSet().rangeByScore(FROZEN, 0, now, 0, Math.min(batchSize, 100));
            if (frozen != null) {
                for (String sku : frozen) {
                    try { synchronize(Long.parseLong(sku)); }
                    catch (RuntimeException exception) {
                        LOG.warn("冻结SKU恢复暂未完成，SKU {}，错误类型 {}", sku, exception.getClass().getSimpleName());
                    }
                }
            }
        } catch (RuntimeException exception) {
            LOG.warn("Redis预扣恢复扫描暂不可用，错误类型 {}", exception.getClass().getSimpleName());
        }
    }

    public static String releaseEventId(byte[] token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("stock-reservation-release:" + tokenHex(token)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少SHA-256", exception);
        }
    }

    /** 数据库补偿与Rabbit消费共用同一快照；只在存在尚未协调的库存事件时触发。 */
    public boolean needsInventoryReconciliation(long skuId, long inventorySequence) {
        requireOutsideTransaction();
        String applied = string(redis.opsForHash().get(skuKey(skuId), "inventorySequence"));
        return applied.isBlank() || Long.parseLong(applied) < inventorySequence;
    }

    private void recoverToken(String token) {
        byte[] tokenBytes;
        try { tokenBytes = HexFormat.of().parseHex(token); }
        catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Redis预扣恢复索引格式异常");
        }
        PurchaseRequest request = requests.byReservationToken(tokenBytes);
        Map<Object, Object> values = redis.opsForHash().entries(tokenKey(tokenBytes));
        if (request != null) {
            if (values.isEmpty() && !request.terminal()) {
                synchronize(request.skuId());
                return;
            }
            try {
                verify(request, tokenBytes, values);
            } catch (IllegalStateException inconsistency) {
                acquireFreeze(request.skuId());
                throw inconsistency;
            }
            if (request.terminal()) {
                settle(request.skuId(), tokenBytes, request.status());
            }
            else bind(tokenBytes, request.id());
            return;
        }
        if (values.isEmpty()) throw new IllegalStateException("Redis预扣恢复索引缺少token凭据");
        if (!string(values.get("requestId")).isBlank()) {
            LOG.error("Redis预扣指向缺失的数据库申请，SKU {} 保持停卖并等待人工核对", values.get("skuId"));
            redis.execute(DEFER, List.of(tokenKey(tokenBytes), RECOVERY), token, Long.toString(System.currentTimeMillis() + ORPHAN_GRACE_MS));
            synchronize(parseLong(values, "skuId"));
            return;
        }
        String owner = UUID.randomUUID().toString();
        Long claim = redis.execute(CLAIM_RECOVERY, List.of(tokenKey(tokenBytes), RECOVERY),
                token, owner, Long.toString(System.currentTimeMillis()), Long.toString(RECOVERY_LEASE_MS));
        if (claim == null || claim != 1) return;
        StockReservation reservation = reservation(tokenBytes, values);
        PurchaseAdmissionTransaction.OrphanResolution resolution = admission.recoverOrphan(reservation);
        if (resolution.release()) {
            settle(reservation.skuId(), tokenBytes, "FAILED", owner);
        } else if (resolution.request() != null) {
            if (resolution.request().terminal()) {
                settle(resolution.request().skuId(), tokenBytes, resolution.request().status());
            }
            else bind(tokenBytes, resolution.request().id());
        }
    }

    /** mutation先于MySQL快照读取；任何并发结算都会使本次发布失效，最多重读五次。 */
    private StockSnapshotService.InventorySnapshot reconcileFrozen(long skuId, FreezeLease lease, boolean reopen) {
        for (int attempt = 0; attempt < FREEZE_RECONCILE_ATTEMPTS; attempt++) {
            long expectedMutation = mutation(skuId);
            StockSnapshotService.InventorySnapshot snapshot = snapshots.lockAndRead(skuId);
            boolean changed = false;
            for (PurchaseRequest active : snapshot.activeRequests()) {
                String token = tokenHex(active.stockReservationToken());
                Map<Object, Object> fields = redis.opsForHash().entries(tokenKey(token));
                if (!fields.isEmpty()) verify(active, active.stockReservationToken(), fields);
                if (fields.isEmpty() || redis.opsForZSet().score(skuTokensKey(skuId), token) == null) {
                    if (!restore(active)) { changed = true; break; }
                }
            }
            if (changed) continue;

            long held = 0;
            Set<String> members = redis.opsForZSet().range(skuTokensKey(skuId), 0, -1);
            if (members != null) {
                for (String token : members) {
                    byte[] tokenBytes = HexFormat.of().parseHex(token);
                    Map<Object, Object> fields = redis.opsForHash().entries(tokenKey(token));
                    if (!"RESERVED".equals(string(fields.get("state")))) {
                        if (mutation(skuId) != expectedMutation) { changed = true; break; }
                        throw new IllegalStateException("Redis活动预扣索引与凭据状态不一致，保持SKU冻结");
                    }
                    PurchaseRequest request = requests.byReservationToken(tokenBytes);
                    if (request == null) {
                        if (!string(fields.get("requestId")).isBlank()) {
                            throw new IllegalStateException("Redis预扣指向缺失数据库申请，保持SKU冻结");
                        }
                        StockReservation orphan = reservation(tokenBytes, fields);
                        if (orphan.skuId() != skuId) throw new IllegalStateException("Redis孤儿token所属SKU不一致");
                        held = Math.addExact(held, orphan.quantity());
                    } else {
                        verify(request, tokenBytes, fields);
                        if (request.terminal()) {
                            if (!settle(skuId, tokenBytes, request.status())) throw unavailable(null);
                            changed = true;
                            break;
                        }
                        held = Math.addExact(held, request.quantity());
                    }
                }
            }
            if (changed || mutation(skuId) != expectedMutation) continue;
            long available = snapshot.databaseAvailable() - held;
            if (available < 0) throw new IllegalStateException("Redis预扣总量超过MySQL可售库存，保持SKU冻结");
            Long written = redis.execute(SNAPSHOT, List.of(skuKey(skuId), FROZEN), lease.owner(),
                    Long.toString(lease.fence()), Long.toString(available), Integer.toString(snapshot.version()),
                    snapshot.eligible() ? "1" : "0", Long.toString(expectedMutation),
                    Long.toString(snapshot.saleStartsAt()), Long.toString(snapshot.saleEndsAt()), Long.toString(skuId),
                    reopen ? "1" : "0", Long.toString(snapshot.inventorySequence()));
            if (written != null && written == 1) return snapshot;
            if (written == null || written != -1) throw new IllegalStateException("Redis库存快照租约已失效");
        }
        throw new IllegalStateException("Redis预扣持续并发变化，SKU保持冻结并等待恢复扫描");
    }

    private boolean restore(PurchaseRequest request) {
        Long restored = redis.execute(RESTORE,
                List.of(skuKey(request.skuId()), tokenKey(request.stockReservationToken()),
                        skuTokensKey(request.skuId()), RECOVERY),
                tokenHex(request.stockReservationToken()), Long.toString(request.userId()), Long.toString(request.skuId()),
                Integer.toString(request.quantity()), request.idempotencyKey(), HexFormat.of().formatHex(request.requestHash()),
                Long.toString(request.id()), Long.toString(System.currentTimeMillis() + ORPHAN_GRACE_MS));
        if (restored != null && restored == 0) return false; // 快照读取后申请已完成，重读MySQL。
        if (restored == null || restored != 1) throw new IllegalStateException("无法从MySQL事实恢复Redis活动预扣");
        return true;
    }

    private void ensurePendingCounter() {
        String owner = UUID.randomUUID().toString();
        Long state = redis.execute(CAPACITY_INIT, List.of(PENDING, CAPACITY_INIT_KEY), owner, Long.toString(INIT_LEASE_MS));
        if (state == null) throw unavailable(null);
        if (state == 0) return;
        if (state != 1) throw unavailable(1L);

        // 计数缺失时结算Lua拒绝删除活动记录；先补齐SQL活动凭据，避免申请过期后留下无法释放的计数。
        Set<String> pending = new HashSet<>();
        Set<Long> frozenSkus = new HashSet<>();
        List<PurchaseRequest> active = requests.activeReservations(TOKEN_SCAN_LIMIT + 1);
        if (active.size() > TOKEN_SCAN_LIMIT) throw new IllegalStateException("MySQL活动申请超出有界恢复范围");
        for (PurchaseRequest request : active) {
            String token = tokenHex(request.stockReservationToken());
            if (!Boolean.TRUE.equals(redis.hasKey(tokenKey(token)))) {
                if (frozenSkus.add(request.skuId())) acquireFreeze(request.skuId());
                if (!restore(request)) continue;
            }
            if ("RESERVED".equals(string(redis.opsForHash().get(tokenKey(token), "state")))) pending.add(token);
        }
        Long tokenCount = redis.opsForZSet().zCard(RECOVERY);
        if (tokenCount != null && tokenCount > TOKEN_SCAN_LIMIT) throw new IllegalStateException("Redis活动token超出有界恢复范围");
        Set<String> tokens = redis.opsForZSet().range(RECOVERY, 0, TOKEN_SCAN_LIMIT - 1);
        if (tokens != null) {
            for (String token : tokens) {
                if (!"RESERVED".equals(string(redis.opsForHash().get(tokenKey(token), "state")))) {
                    throw new IllegalStateException("Redis恢复索引包含异常凭据，停止重建容量");
                }
                pending.add(token);
            }
        }
        Long published = redis.execute(CAPACITY_PUBLISH, List.of(PENDING, CAPACITY_INIT_KEY), owner, Integer.toString(pending.size()));
        if (published == null || published != 1) throw unavailable(1L);
    }

    private FreezeLease acquireFreeze(long skuId) {
        String owner = UUID.randomUUID().toString();
        List<?> result = redis.execute(FREEZE, List.of(skuKey(skuId), FROZEN, FENCE), Long.toString(skuId), owner,
                Long.toString(FREEZE_LEASE_MS), Long.toString(System.currentTimeMillis()));
        if (result == null || first(result) != 1) throw unavailable(1L);
        return new FreezeLease(owner, second(result));
    }

    private long mutation(long skuId) {
        String value = string(redis.opsForHash().get(skuKey(skuId), "mutation"));
        return value.isBlank() ? 0 : Long.parseLong(value);
    }

    private long currentAvailable(long skuId) {
        String value = string(redis.opsForHash().get(skuKey(skuId), "available"));
        if (value.isBlank()) throw new IllegalStateException("Redis冻结SKU缺少可售库存快照");
        return Long.parseLong(value);
    }

    private StockReservation reservation(byte[] token, Map<Object, Object> fields) {
        byte[] hash = HexFormat.of().parseHex(required(fields, "requestHash"));
        int quantity = Math.toIntExact(parseLong(fields, "quantity"));
        if (token.length != 32 || hash.length != 32 || quantity < 1) {
            throw new IllegalStateException("Redis预扣凭据长度或数量异常");
        }
        return new StockReservation(token, parseLong(fields, "userId"), parseLong(fields, "skuId"),
                quantity, required(fields, "idempotencyKey"), hash);
    }

    private void verify(PurchaseRequest request, byte[] token, Map<Object, Object> fields) {
        if (request.stockReservationToken() == null || request.stockReservationToken().length != 32
                || !MessageDigest.isEqual(request.stockReservationToken(), token)) {
            throw new IllegalStateException("MySQL购票申请token与恢复索引不一致");
        }
        StockReservation reservation = reservation(token, fields);
        if (reservation.userId() != request.userId() || reservation.skuId() != request.skuId()
                || reservation.quantity() != request.quantity()
                || !reservation.idempotencyKey().equals(request.idempotencyKey())
                || !MessageDigest.isEqual(reservation.requestHash(), request.requestHash())) {
            throw new IllegalStateException("Redis预扣参数与MySQL购票申请不一致");
        }
        String requestId = string(fields.get("requestId"));
        if (!requestId.isBlank() && !requestId.equals(Long.toString(request.id()))) {
            throw new IllegalStateException("Redis预扣绑定了不同的MySQL购票申请");
        }
    }

    private void requireOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Redis库存网络IO不能位于数据库事务内");
        }
    }

    private BusinessException unavailable(Long retryAfter) {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "PURCHASE_ADMISSION_UNAVAILABLE",
                "购票入口暂不可用，请稍后使用原申请重试", retryAfter);
    }

    private static long first(List<?> result) {
        if (result == null || result.isEmpty() || !(result.getFirst() instanceof Number number)) {
            throw new IllegalStateException("Redis库存脚本返回异常");
        }
        return number.longValue();
    }

    private static long second(List<?> result) {
        if (result.size() < 2 || !(result.get(1) instanceof Number number)) {
            throw new IllegalStateException("Redis冻结脚本返回异常");
        }
        return number.longValue();
    }

    private static String string(Object value) { return value == null ? "" : value.toString(); }
    private static String required(Map<Object, Object> values, String field) {
        String value = string(values.get(field));
        if (value.isBlank()) throw new IllegalStateException("Redis预扣凭据缺少必要字段 " + field);
        return value;
    }
    private static long parseLong(Map<Object, Object> values, String field) {
        return Long.parseLong(required(values, field));
    }
    private static String tokenHex(byte[] token) { return HexFormat.of().formatHex(token); }
    private static String tokenKey(byte[] token) { return tokenKey(tokenHex(token)); }
    private static String tokenKey(String token) { return PREFIX + "reservation:" + token; }
    private static String skuKey(long skuId) { return PREFIX + "sku:" + skuId; }
    private static String skuTokensKey(long skuId) { return PREFIX + "sku-reservations:" + skuId; }
    private static DefaultRedisScript<List> listScript(String path) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(List.class);
        return script;
    }
    private static DefaultRedisScript<Long> longScript(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }

    private record FreezeLease(String owner, long fence) { }
}
