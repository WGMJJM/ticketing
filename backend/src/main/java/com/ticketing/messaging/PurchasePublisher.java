package com.ticketing.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
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
    private final RabbitTemplate rabbit;
    private final ObjectMapper json;
    private final String exchange;
    private final String ttl;
    private final MeterRegistry metrics;

    public PurchasePublisher(RabbitTemplate rabbit, ObjectMapper json,
            @Value("${ticketing.purchase.exchange:ticketing.purchase}") String exchange,
            @Value("${ticketing.purchase.queue-seconds:120}") int seconds, MeterRegistry metrics) {
        this.rabbit = rabbit;
        this.json = json;
        this.exchange = exchange;
        this.ttl = Long.toString(seconds * 1000L);
        this.metrics = metrics;
    }

    public void publish(long requestId, long skuId) {
        try {
            MessageProperties properties = new MessageProperties();
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setMessageId(Long.toString(requestId));
            properties.setExpiration(ttl);
            rabbit.send(exchange, PurchaseCommand.ROUTING_KEY,
                    new Message(json.writeValueAsBytes(new PurchaseCommand(requestId, skuId)), properties));
        } catch (RuntimeException | JsonProcessingException exception) {
            metrics.counter("ticketing.purchase.publish.failure").increment();
            LOG.warn("购票消息发送失败，申请 {}，票档 {}，类型 {}，等待恢复", requestId, skuId,
                    exception.getClass().getSimpleName());
        }
    }
}
