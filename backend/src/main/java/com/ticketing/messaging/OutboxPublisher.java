package com.ticketing.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 单个调度线程负责有界在途窗口及落账；Rabbit回调只投递完成通知，不等待或访问数据库。 */
@Component
public class OutboxPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxService service;
    private final RabbitTemplate rabbit;
    private final String exchange;
    private final String purchaseExchange;
    private final String purchaseMessageTtl;
    private final int batchSize;
    private final int window;
    private final long confirmNanos;
    private final MeterRegistry meters;
    private volatile boolean stopping;

    public OutboxPublisher(OutboxService service, RabbitTemplate rabbit,
            @Value("${ticketing.waitlist.exchange:ticketing.waitlist}") String exchange,
            @Value("${ticketing.purchase.exchange:ticketing.purchase}") String purchaseExchange,
            @Value("${ticketing.purchase.queue-seconds:120}") int queueSeconds,
            OutboxProperties properties, MeterRegistry meters) {
        this.service = service;
        this.rabbit = rabbit;
        this.exchange = exchange;
        this.purchaseExchange = purchaseExchange;
        this.purchaseMessageTtl = Long.toString(queueSeconds * 1000L);
        this.batchSize = properties.batchSize();
        this.window = properties.inFlightWindow();
        this.confirmNanos = TimeUnit.MILLISECONDS.toNanos(properties.confirmTimeoutMs());
        this.meters = meters;
    }

    public synchronized void publishBatch() {
        int claimed = 0;
        while (!stopping && !Thread.currentThread().isInterrupted() && claimed < batchSize) {
            List<OutboxEvent> events = service.claimBatch(Math.min(window, batchSize - claimed));
            if (events.isEmpty()) return;
            for (OutboxEvent event : events) {
                if (OutboxStatus.PROCESSING.name().equals(event.getStatus()))
                    DeliveryObservation.record(event.getEventId()+":"+event.getRetryCount(), "outbox-claim-commit");
            }
            claimed += events.size();
            if (!publishWindow(events)) return; // 故障不再认领新窗口，已在途的健康消息独立完成。
        }
    }

    private boolean publishWindow(List<OutboxEvent> events) {
        var completed = new ArrayBlockingQueue<Confirmation>(events.size());
        Map<String, Delivery> pending = new LinkedHashMap<>();
        boolean healthy = true;
        for (OutboxEvent event : events) {
            if (!OutboxStatus.PROCESSING.name().equals(event.getStatus())) continue;
            count(event, event.getRetryCount() > 1 ? "retry" : "claimed");
            if (!healthy || stopping || Thread.currentThread().isInterrupted()) {
                count(event, "lease-recovery");
                continue;
            }
            var correlation = new CorrelationData(event.getEventId() + ":" + event.getRetryCount());
            Delivery delivery = new Delivery(event, correlation, System.nanoTime());
            pending.put(correlation.getId(), delivery);
            correlation.getFuture().whenComplete((confirm, failure) ->
                    completed.offer(new Confirmation(correlation.getId(), System.nanoTime(), failure)));
            try {
                MessageProperties properties = new MessageProperties();
                properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                properties.setMessageId(event.getEventId());
                properties.setCorrelationId(event.getEventId() + ":" + event.getRetryCount());
                boolean purchase = PurchaseCommand.TYPE.equals(event.getEventType());
                if (purchase) properties.setExpiration(purchaseMessageTtl);
                rabbit.send(purchase ? purchaseExchange : exchange,
                        purchase ? PurchaseCommand.ROUTING_KEY : InventoryEvent.ROUTING_KEY,
                        new Message(event.getPayload().getBytes(StandardCharsets.UTF_8), properties), correlation);
            } catch (RuntimeException failure) {
                pending.remove(correlation.getId());
                failed(event, "send-failure");
                healthy = false;
            }
        }
        while (!pending.isEmpty() && !stopping && !Thread.currentThread().isInterrupted()) {
            try {
                long now = System.nanoTime();
                long nearest = pending.values().stream().mapToLong(d -> d.started() + confirmNanos).min().orElse(now);
                Confirmation confirmation = completed.poll(Math.max(0, Math.min(nearest - now,
                        TimeUnit.MILLISECONDS.toNanos(100))), TimeUnit.NANOSECONDS);
                if (confirmation != null) {
                    Delivery delivery = pending.remove(confirmation.id());
                    if (delivery != null) healthy &= complete(delivery, confirmation);
                }
                now = System.nanoTime();
                for (Delivery delivery : new ArrayList<>(pending.values())) {
                    if (!delivery.correlation().getFuture().isDone() && now - delivery.started() >= confirmNanos) {
                        pending.remove(delivery.correlation().getId());
                        failed(delivery.event(), "confirm-timeout");
                        healthy = false;
                    }
                }
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                healthy = false;
            }
        }
        // 迟到回调只写当前局部完成队列；进程消失后依靠持久租约与递增令牌恢复。
        pending.values().forEach(delivery -> count(delivery.event(), "lease-recovery"));
        return healthy && pending.isEmpty() && !stopping && !Thread.currentThread().isInterrupted();
    }

    private boolean complete(Delivery delivery, Confirmation confirmation) {
        String outcome = "confirmed";
        try {
            if (confirmation.failure() != null) throw new IllegalStateException("代理确认失败");
            if (confirmation.completed() - delivery.started() >= confirmNanos) throw new TimeoutException("代理确认超时");
            awaitConfirm(delivery.correlation());
            DeliveryObservation.record(delivery.correlation().getId(), "broker-confirm", confirmation.completed());
            boolean applied = service.sent(delivery.event());
            count(delivery.event(), applied ? "sent" : "stale-confirm");
            return true;
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            outcome = "interrupted";
            count(delivery.event(), "lease-recovery");
            return false;
        } catch (Exception failure) {
            outcome = delivery.correlation().getReturned() != null ? "returned" : "unconfirmed";
            failed(delivery.event(), outcome);
            return false;
        } finally {
            Timer.builder("ticketing.outbox.confirm.duration").tags("channel", channel(delivery.event()), "outcome", outcome)
                    .publishPercentiles(.95,.99).register(meters)
                    .record(Math.max(0, confirmation.completed() - delivery.started()), TimeUnit.NANOSECONDS);
        }
    }

    /** 完成通知后非阻塞核验，保留原有确认故障注入边界。Return须先于Future完成。 */
    protected void awaitConfirm(CorrelationData correlation) throws Exception {
        CorrelationData.Confirm confirm = correlation.getFuture().getNow(null);
        if (confirm == null || !confirm.isAck() || correlation.getReturned() != null) {
            throw new IllegalStateException("代理未确认或消息未路由");
        }
    }

    private void failed(OutboxEvent event, String outcome) {
        count(event, outcome);
        LOG.warn("{}消息投递未确认，结果 {}", channel(event), outcome);
        try {
            if (!service.failed(event)) count(event, "stale-failure");
        } catch (RuntimeException databaseFailure) {
            count(event, "lease-recovery");
            LOG.warn("{}消息重试状态暂未写入，等待数据库租约恢复", channel(event));
        }
    }

    private void count(OutboxEvent event, String outcome) {
        meters.counter("ticketing.outbox.publish", "channel", channel(event), "outcome", outcome).increment();
    }

    private String channel(OutboxEvent event) {
        return PurchaseCommand.TYPE.equals(event.getEventType()) ? "purchase" : "inventory";
    }

    @PreDestroy
    public void shutdown() { stopping = true; }

    private record Delivery(OutboxEvent event, CorrelationData correlation, long started) { }
    private record Confirmation(String id, long completed, Throwable failure) { }
}
