package com.ticketing.common;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.dao.QueryTimeoutException;

/** 仅隔离诊断模式创建；事件不包含 Redis key、请求标识或异常正文。 */
public final class RedisCommandDiagnostics {
    private static final int EVENT_LIMIT = 256;
    private static final int FAILURE_EVENT_LIMIT = 128;
    private final MeterRegistry registry;
    private final ArrayDeque<Map<String, Object>> recent = new ArrayDeque<>();
    private final ArrayDeque<Map<String, Object>> failures = new ArrayDeque<>();
    private final com.github.benmanes.caffeine.cache.Cache<Long, Long> inventoryPauses =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(1024).build();

    public RedisCommandDiagnostics(MeterRegistry registry) { this.registry = registry; }

    public long start() { return System.nanoTime(); }

    /** 仅诊断模式内存关联；不输出 SKU 标识，不因重复扫描重复计数。 */
    public void inventoryPaused(long skuId) {
        if (inventoryPauses.asMap().putIfAbsent(skuId, start()) == null) {
            registry.counter("ticketing.diagnostics.inventory.pauses").increment();
        }
    }

    public void inventoryReady(long skuId) {
        Long paused = inventoryPauses.asMap().remove(skuId);
        if (paused != null) success("inventory.paused", "ready", paused, Map.of(), true);
    }

    public void success(String stage, String result, long started, Map<String, ?> facts, boolean keepEvent) {
        record(stage, result, null, started, facts, keepEvent, false);
    }

    public void failure(String stage, Throwable exception, long started, Map<String, ?> facts) {
        record(stage, "exception", exception, started, facts, true, true);
    }

    public void branch(String stage, int code, long started, Map<String, ?> facts) {
        String branch = switch (code) {
            case 1 -> "1"; case 2 -> "2"; case -1 -> "minus_1"; case -2 -> "minus_2";
            case -3 -> "minus_3"; case -6 -> "minus_6"; case -10 -> "minus_10";
            case -11 -> "minus_11"; case -12 -> "minus_12"; case -13 -> "minus_13";
            default -> "other";
        };
        boolean unexpected = "other".equals(branch);
        record(stage, "lua_" + branch, null, started, facts,
                code == -3 || unexpected, code < 0 || unexpected);
    }

    private void record(String stage, String result, Throwable exception, long started,
            Map<String, ?> facts, boolean keepEvent, boolean keepFailure) {
        long elapsed = Math.max(0, System.nanoTime() - started);
        String failureKind = exception == null ? "none" : classify(exception);
        Timer.builder("ticketing.diagnostics.redis.duration")
                .tag("stage", stage).tag("result", result).tag("failureKind", failureKind)
                .register(registry).record(elapsed, TimeUnit.NANOSECONDS);
        if (!keepEvent && !keepFailure) return;
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("timeMs", System.currentTimeMillis());
        event.put("stage", stage); event.put("result", result);
        event.put("elapsedMs", TimeUnit.NANOSECONDS.toMillis(elapsed));
        event.put("failureKind", failureKind);
        if (exception != null) event.put("rootCauseClass", rootCause(exception).getClass().getSimpleName());
        if (facts != null) facts.forEach((key, value) -> {
            if (List.of("requestIdKnown", "reserveOutcomeKnown", "reserveBranch", "ready", "pendingKeyExists",
                    "pending", "waitlistPriority", "reason", "pendingCount", "sessionRead", "route",
                    "waitlistRequestPresent").contains(key)) {
                event.put(key, value);
            }
        });
        synchronized (recent) {
            Map<String, Object> snapshot = Map.copyOf(event);
            if (keepEvent) {
                if (recent.size() == EVENT_LIMIT) recent.removeFirst();
                recent.addLast(snapshot);
            }
            if (keepFailure) {
                if (failures.size() == FAILURE_EVENT_LIMIT) failures.removeFirst();
                failures.addLast(snapshot);
            }
        }
    }

    public Map<String, Object> snapshot() {
        synchronized (recent) {
            return Map.of("events", new ArrayList<>(recent), "limit", EVENT_LIMIT,
                    "failures", new ArrayList<>(failures), "failureLimit", FAILURE_EVENT_LIMIT);
        }
    }

    private static String classify(Throwable exception) {
        Throwable root = rootCause(exception);
        String name = root.getClass().getSimpleName();
        if (root instanceof QueryTimeoutException || name.contains("Timeout")) return "timeout";
        if (name.contains("Connection") || name.contains("ConnectException") || name.contains("ClosedChannel")) return "connection";
        if (name.contains("Codec") || name.contains("Serialization") || name.contains("Decode")) return "decode";
        if (exception instanceof IllegalStateException || exception instanceof ClassCastException
                || root instanceof NumberFormatException) return "malformed_reply";
        return "other";
    }

    private static Throwable rootCause(Throwable exception) {
        Throwable current = exception;
        for (int i = 0; i < 8 && current.getCause() != null && current.getCause() != current; i++) current = current.getCause();
        return current;
    }
}
