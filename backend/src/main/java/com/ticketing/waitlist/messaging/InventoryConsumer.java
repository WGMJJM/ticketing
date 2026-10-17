package com.ticketing.waitlist.messaging;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.waitlist.entity.OutboxEvent;
import com.ticketing.waitlist.mapper.OutboxMapper;
import com.ticketing.waitlist.service.WaitlistDispatcher;
import java.io.IOException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="ticketing.waitlist.messaging.enabled",matchIfMissing=true)
public class InventoryConsumer {
    private final ObjectMapper json;
    private final OutboxMapper outbox;
    private final WaitlistDispatcher dispatcher;
    public InventoryConsumer(ObjectMapper json,OutboxMapper outbox,WaitlistDispatcher dispatcher) {
        this.json=json; this.outbox=outbox; this.dispatcher=dispatcher;
    }
    @RabbitListener(id="waitlistDispatch",queues="${ticketing.waitlist.queue:ticketing.waitlist.dispatch}")
    public void consume(Message message) {
        JsonNode payload;
        try { payload=json.readTree(message.getBody()); }
        catch (IOException exception) { throw new AmqpRejectAndDontRequeueException("库存事件格式不正确"); }
        String eventId=payload==null?"":payload.path("eventId").asText();
        if (eventId.isBlank() || eventId.length()>64 || !eventId.equals(message.getMessageProperties().getMessageId())) {
            throw new AmqpRejectAndDontRequeueException("库存事件标识不正确");
        }
        OutboxEvent event=outbox.selectOne(new LambdaQueryWrapper<OutboxEvent>().eq(OutboxEvent::getEventId,eventId));
        if (event==null || !InventoryEventWriter.TYPE.equals(event.getEventType()) || !"TICKET_SKU".equals(event.getAggregateType())
                || !event.getAggregateId().toString().equals(payload.path("skuId").asText())) {
            throw new AmqpRejectAndDontRequeueException("库存事件与已提交数据库事实不一致");
        }
        // AUTO确认在本方法返回后进行；每笔分配事务此前均已提交。重复消息只重查队列。
        dispatcher.dispatch(event.getAggregateId());
    }
}
