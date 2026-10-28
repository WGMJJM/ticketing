package com.ticketing.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 并发 HTTP/恢复共享有界在途窗口；回调只完成结果，不访问数据库或 Redis。 */
@Component
public class PurchasePublisher {
    private final RabbitTemplate rabbit;
    private final ObjectMapper json;
    private final String exchange;
    private final String ttl;
    private final Semaphore slots;
    private final int timeoutMs;
    private final MeterRegistry metrics;
    private final boolean diagnosticsEnabled;

    public PurchasePublisher(RabbitTemplate rabbit, ObjectMapper json,
            @Value("${ticketing.purchase.exchange:ticketing.purchase}") String exchange,
            @Value("${ticketing.purchase.queue-seconds:120}") int seconds,
            @Value("${ticketing.purchase.in-flight-window:256}") int window,
            @Value("${ticketing.purchase.confirm-timeout-ms:3000}") int timeoutMs,
            MeterRegistry metrics, @Value("${ticketing.diagnostics.enabled:false}") boolean diagnosticsEnabled) {
        this.rabbit = rabbit; this.json = json; this.exchange = exchange;
        this.ttl = Long.toString(seconds * 1000L); this.slots = new Semaphore(window); this.timeoutMs = timeoutMs;
        this.metrics = metrics; this.diagnosticsEnabled = diagnosticsEnabled;
    }

    public CompletableFuture<Boolean> publish(long requestId, long skuId) {
        if (!slots.tryAcquire()) {
            observe("slot_full");
            return CompletableFuture.completedFuture(false);
        }
        CorrelationData correlation = new CorrelationData();
        CompletableFuture<Boolean> result = correlation.getFuture().handle((confirm, failure) -> {
            slots.release();
            observe(failure != null ? "callback_failure" : confirm == null ? "missing_confirm"
                    : !confirm.isAck() ? "nack" : correlation.getReturned() != null ? "returned" : "ack");
            return failure == null && confirm != null && confirm.isAck() && correlation.getReturned() == null;
        });
        try {
            MessageProperties properties = new MessageProperties();
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setMessageId(Long.toString(requestId));
            properties.setExpiration(ttl);
            rabbit.send(exchange, PurchaseCommand.ROUTING_KEY,
                    new Message(json.writeValueAsBytes(new PurchaseCommand(requestId, skuId)), properties), correlation);
        } catch (RuntimeException | JsonProcessingException exception) {
            correlation.getFuture().completeExceptionally(exception);
        }
        return result;
    }

    public boolean confirm(long requestId, long skuId) {
        try { return publish(requestId, skuId).get(timeoutMs, TimeUnit.MILLISECONDS); }
        catch (InterruptedException exception) { observe("interrupted"); Thread.currentThread().interrupt(); return false; }
        catch (java.util.concurrent.ExecutionException exception) { observe("execution_failure"); return false; }
        catch (java.util.concurrent.TimeoutException exception) { observe("confirm_timeout"); return false; }
    }

    private void observe(String reason) {
        if (diagnosticsEnabled) metrics.counter("ticketing.diagnostics.publisher.result", "reason", reason).increment();
    }
}
