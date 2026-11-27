package com.ticketing.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 发送失败保留 Redis 预扣，由原恢复索引重新投递或仲裁过期。 */
@Component
public class PurchasePublisher {
    private static final Logger LOG = LoggerFactory.getLogger(PurchasePublisher.class);
    private static final int FAILURE_THRESHOLD = 3;
    private final RabbitTemplate rabbit;
    private final ObjectMapper json;
    private final String exchange;
    private final String queue;
    private final String ttl;
    private final long probeDelayNanos;
    private final MeterRegistry metrics;
    private final AtomicReference<State> state = new AtomicReference<>(new State(0, 0, 0, false));

    public PurchasePublisher(RabbitTemplate rabbit, ObjectMapper json,
            @Value("${ticketing.purchase.exchange:ticketing.purchase}") String exchange,
            @Value("${ticketing.purchase.queue:ticketing.purchase.orders}") String queue,
            @Value("${ticketing.purchase.queue-seconds:120}") int seconds,
            @Value("${ticketing.purchase.probe-delay-ms:5000}") long probeDelayMs, MeterRegistry metrics) {
        this.rabbit = rabbit;
        this.json = json;
        this.exchange = exchange;
        this.queue = queue;
        this.ttl = Long.toString(seconds * 1000L);
        this.probeDelayNanos = TimeUnit.MILLISECONDS.toNanos(probeDelayMs);
        this.metrics = metrics;
        metrics.gauge("ticketing.purchase.publish.paused", this, value -> value.admitting() ? 0 : 1);
    }

    public boolean admitting() {
        return !state.get().paused();
    }

    public void publish(long requestId, long skuId) {
        State started = state.get();
        if (started.paused()) return;
        try {
            MessageProperties properties = new MessageProperties();
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setMessageId(Long.toString(requestId));
            properties.setExpiration(ttl);
            rabbit.send(exchange, PurchaseCommand.ROUTING_KEY,
                    new Message(json.writeValueAsBytes(new PurchaseCommand(requestId, skuId)), properties));
            state.updateAndGet(current -> current.generation() == started.generation() && current.failures() > 0
                    ? new State(current.generation(), 0, 0, false) : current);
        } catch (RuntimeException | JsonProcessingException exception) {
            State previous = state.getAndUpdate(current -> current.generation() == started.generation()
                    ? onFailure(current) : current);
            if (previous.generation() == started.generation() && previous.failures() == FAILURE_THRESHOLD - 1) {
                LOG.warn("购票消息连续发送失败，暂停新预扣");
            }
            metrics.counter("ticketing.purchase.publish.failure").increment();
            LOG.warn("购票消息发送失败，申请 {}，票档 {}，类型 {}，等待恢复", requestId, skuId,
                    exception.getClass().getSimpleName());
        }
    }

    private State onFailure(State current) {
        int failures = current.failures() + 1;
        if (failures < FAILURE_THRESHOLD) return new State(current.generation(), failures, 0, false);
        return new State(current.generation() + 1, failures, System.nanoTime() + probeDelayNanos, false);
    }

    /** 暂停期间只由恢复任务探测，避免并发请求反复连接 RabbitMQ。 */
    public void probeRecovery() {
        State paused = state.get();
        if (!paused.paused() || paused.probing() || System.nanoTime() - paused.retryAt() < 0) return;
        State probing = new State(paused.generation(), paused.failures(), paused.retryAt(), true);
        if (!state.compareAndSet(paused, probing)) return;
        try {
            rabbit.execute(channel -> {
                channel.exchangeDeclarePassive(exchange);
                channel.queueDeclarePassive(queue);
                return null;
            });
            state.set(new State(probing.generation(), 0, 0, false));
            LOG.info("购票消息通道探测通过，恢复新预扣");
        } catch (RuntimeException exception) {
            state.set(new State(probing.generation(), probing.failures(), System.nanoTime() + probeDelayNanos, false));
            LOG.warn("购票消息通道仍不可用，类型 {}", exception.getClass().getSimpleName());
        }
    }

    private record State(long generation, int failures, long retryAt, boolean probing) {
        boolean paused() { return failures >= FAILURE_THRESHOLD; }
    }
}
