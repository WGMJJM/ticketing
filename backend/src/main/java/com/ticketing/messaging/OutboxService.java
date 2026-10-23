package com.ticketing.messaging;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxService {
    private final OutboxMapper outbox;
    private final int leaseSeconds;
    private final int maxAttempts;
    private final int baseSeconds;
    private final int maxSeconds;
    private final java.util.concurrent.atomic.AtomicInteger channelTurn = new java.util.concurrent.atomic.AtomicInteger();

    public OutboxService(OutboxMapper outbox, OutboxProperties properties) {
        this.outbox = outbox;
        this.leaseSeconds = properties.leaseSeconds();
        this.maxAttempts = properties.maxAttempts();
        this.baseSeconds = properties.retryBaseSeconds();
        this.maxSeconds = properties.retryMaxSeconds();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OutboxEvent claimNext() {
        boolean purchase = (channelTurn.getAndIncrement() & 1) == 1;
        String first = purchase ? PurchaseCommand.TYPE : InventoryEvent.TYPE;
        String second = purchase ? InventoryEvent.TYPE : PurchaseCommand.TYPE;
        OutboxEvent event = outbox.claimCandidate(first);
        if (event == null) event = outbox.claimCandidate(second);
        if (event == null) {
            return null;
        }
        if (event.getRetryCount() >= maxAttempts) {
            outbox.update(
                    null,
                    new LambdaUpdateWrapper<OutboxEvent>()
                            .eq(OutboxEvent::getId, event.getId())
                            .set(OutboxEvent::getStatus, OutboxStatus.FAILED.name())
                            .set(OutboxEvent::getLastError, "投递次数已达上限，请检查消息链路"));
            event.setStatus(OutboxStatus.FAILED.name());
            return event;
        }
        outbox.claim(event.getId(), leaseSeconds);
        return outbox.selectById(event.getId());
    }

    public boolean sent(OutboxEvent event) {
        return outbox.sent(event.getId(), event.getRetryCount()) == 1;
    }

    public void failed(OutboxEvent event) {
        long delay = baseSeconds;
        for (int index = 1; index < event.getRetryCount() && delay < maxSeconds; index++) {
            delay = Math.min((long) maxSeconds, delay * 2);
        }
        outbox.failed(
                event.getId(),
                event.getRetryCount(),
                event.getRetryCount() >= maxAttempts
                        ? OutboxStatus.FAILED.name()
                        : OutboxStatus.PENDING.name(),
                (int) delay,
                "消息未确认或路由失败，请检查消息链路");
    }
}
