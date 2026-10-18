package com.ticketing.messaging;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.messaging.entity.OutboxEvent;
import com.ticketing.messaging.mapper.OutboxMapper;
import com.ticketing.messaging.model.InventoryChangeReason;
import com.ticketing.messaging.model.OutboxStatus;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.OptionalLong;
import java.util.UUID;

@Component
public class InventoryEvents {
    private final OutboxMapper outbox;
    private final ObjectMapper json;

    public InventoryEvents(OutboxMapper outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(long skuId, InventoryChangeReason reason) {
        OutboxEvent event = new OutboxEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setAggregateType(InventoryEvent.AGGREGATE);
        event.setAggregateId(skuId);
        event.setEventType(InventoryEvent.TYPE);
        event.setStatus(OutboxStatus.PENDING.name());
        event.setRetryCount(0);
        try {
            event.setPayload(
                    json.writeValueAsString(
                            new InventoryEvent(event.getEventId(), Long.toString(skuId), reason)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("库存事件序列化失败", exception);
        }
        outbox.insert(event);
    }

    /** 消费者只接受已提交的库存事件，避免将Outbox实体与Mapper泄漏给业务模块。 */
    public OptionalLong committedSku(String eventId, String skuId) {
        OutboxEvent event =
                outbox.selectOne(
                        new LambdaQueryWrapper<OutboxEvent>().eq(OutboxEvent::getEventId, eventId));
        if (event == null
                || !InventoryEvent.TYPE.equals(event.getEventType())
                || !InventoryEvent.AGGREGATE.equals(event.getAggregateType())
                || !event.getAggregateId().toString().equals(skuId)) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(event.getAggregateId());
    }
}
