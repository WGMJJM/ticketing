package com.ticketing.messaging;

import com.ticketing.messaging.entity.OutboxEvent;
import com.ticketing.messaging.model.OutboxStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxService service;
    private final RabbitTemplate rabbit;
    private final String exchange;
    private final int batchSize;
    private final int confirmTimeout;

    public OutboxPublisher(
            OutboxService service,
            RabbitTemplate rabbit,
            @Value("${ticketing.waitlist.exchange:ticketing.waitlist}") String exchange,
            OutboxProperties properties) {
        this.service = service;
        this.rabbit = rabbit;
        this.exchange = exchange;
        this.batchSize = properties.batchSize();
        this.confirmTimeout = properties.confirmTimeoutMs();
    }

    public void publishBatch() {
        // 每条发送前单独认领，避免整批消息在等待前序确认时耗尽租约。
        for (int index = 0; index < batchSize; index++) {
            OutboxEvent event = service.claimNext();
            if (event == null) {
                break;
            }
            if (!OutboxStatus.PROCESSING.name().equals(event.getStatus())) {
                continue;
            }
            try {
                MessageProperties properties = new MessageProperties();
                properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                properties.setMessageId(event.getEventId());
                CorrelationData correlation =
                        new CorrelationData(event.getEventId() + ":" + event.getRetryCount());
                rabbit.send(
                        exchange,
                        InventoryEvent.ROUTING_KEY,
                        new Message(
                                event.getPayload().getBytes(StandardCharsets.UTF_8), properties),
                        correlation);
                awaitConfirm(correlation);
                service.sent(event); // retry_count作为认领令牌，旧发布器不能覆盖新认领者。
            } catch (Exception exception) {
                LOG.warn(
                        "库存事件投递未确认，事件 {}，错误类型 {}",
                        event.getEventId(),
                        exception.getClass().getSimpleName());
                try {
                    service.failed(event);
                } catch (RuntimeException databaseFailure) {
                    LOG.warn("事件重试状态暂未写入，等待租约恢复，事件 {}", event.getEventId());
                }
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                // 大批量只用于健康链路；故障后结束本轮，后续认领留给下一次有界重试。
                break;
            }
        }
    }

    protected void awaitConfirm(CorrelationData correlation) throws Exception {
        if (!correlation.getFuture().get(confirmTimeout, TimeUnit.MILLISECONDS).isAck()
                || correlation.getReturned() != null) {
            throw new IllegalStateException("代理未确认或消息未路由");
        }
    }
}
