package com.ticketing.service;

import com.ticketing.config.OutboxProperties;
import com.ticketing.entity.OutboxEvent;
import com.ticketing.enums.OutboxStatus;
import com.ticketing.mapper.OutboxMapper;
import com.ticketing.messaging.InventoryEvent;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.List;
import io.micrometer.core.instrument.MeterRegistry;

@Service
public class OutboxService {
    private final OutboxMapper outbox;
    private final int leaseSeconds;
    private final int maxAttempts;
    private final int baseSeconds;
    private final int maxSeconds;
    private final MeterRegistry meters;

    public OutboxService(OutboxMapper outbox, OutboxProperties properties, MeterRegistry meters) {
        this.outbox = outbox;
        this.meters = meters;
        this.leaseSeconds = properties.leaseSeconds();
        this.maxAttempts = properties.maxAttempts();
        this.baseSeconds = properties.retryBaseSeconds();
        this.maxSeconds = properties.retryMaxSeconds();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OutboxEvent claimNext() {
        List<OutboxEvent> events = claim(1);
        return events.isEmpty() ? null : events.getFirst();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<OutboxEvent> claimBatch(int size) {
        if (size < 1 || size > 100) throw new IllegalArgumentException("Outbox认领数量须为1至100");
        return claim(size);
    }

    private List<OutboxEvent> claim(int size) {
        List<OutboxEvent> selected = outbox.claimCandidates(InventoryEvent.TYPE, size);
        List<Long> ids = new ArrayList<>(selected.size());
        for (OutboxEvent event : selected) {
            // 两个时刻都来自MySQL；重试延迟单独标记，避免将退避混入首次投递分布。
            if (event.getClaimObservedAt() != null && event.getCreatedAt() != null) {
                io.micrometer.core.instrument.Timer.builder("ticketing.outbox.created.to.claim")
                        .tags("channel", "inventory",
                                "attempt", event.getRetryCount() == 0 ? "first" : "retry")
                        .publishPercentiles(.5,.95,.99).register(meters)
                        .record(java.time.Duration.between(event.getCreatedAt(), event.getClaimObservedAt()));
            }
            meters.counter("ticketing.outbox.claim", "channel", "inventory",
                    "outcome", OutboxStatus.PROCESSING.name().equals(event.getStatus()) ? "lease-expired" : "pending").increment();
            if (event.getRetryCount() >= maxAttempts) {
                outbox.update(
                        null,
                        new LambdaUpdateWrapper<OutboxEvent>()
                                .eq(OutboxEvent::getId, event.getId())
                                .set(OutboxEvent::getStatus, OutboxStatus.FAILED.name())
                                .set(OutboxEvent::getLastError, "投递次数已达上限，请检查消息链路"));
                event.setStatus(OutboxStatus.FAILED.name());
            } else {
                ids.add(event.getId());
                event.setRetryCount(event.getRetryCount() + 1);
                event.setStatus(OutboxStatus.PROCESSING.name());
            }
        }
        if (!ids.isEmpty() && outbox.claimBatch(ids, leaseSeconds) != ids.size()) {
            throw new IllegalStateException("Outbox批量认领数量不一致，回滚认领");
        }
        return selected;
    }

    public boolean sent(OutboxEvent event) {
        return outbox.sent(event.getId(), event.getRetryCount()) == 1;
    }

    public boolean failed(OutboxEvent event) {
        long delay = baseSeconds;
        for (int index = 1; index < event.getRetryCount() && delay < maxSeconds; index++) {
            delay = Math.min((long) maxSeconds, delay * 2);
        }
        return outbox.failed(
                event.getId(),
                event.getRetryCount(),
                event.getRetryCount() >= maxAttempts
                        ? OutboxStatus.FAILED.name()
                        : OutboxStatus.PENDING.name(),
                (int) delay,
                "消息未确认或路由失败，请检查消息链路") == 1;
    }
}
