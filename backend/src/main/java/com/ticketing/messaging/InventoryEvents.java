package com.ticketing.messaging;

import com.ticketing.entity.OutboxEvent;
import com.ticketing.enums.InventoryChangeReason;
import com.ticketing.enums.OutboxStatus;
import com.ticketing.mapper.OutboxMapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.OptionalLong;
import java.util.Map;
import java.util.LinkedHashMap;
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
        append(skuId, reason, UUID.randomUUID().toString());
    }

    /** 父级销售资格变更与库存通知同事务持久化，不依赖目录缓存或提交后Redis调用。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void activityConfigurationChanged(long activityId) {
        outbox.inventorySkusForActivity(activityId)
                .forEach(skuId -> append(skuId, InventoryChangeReason.SKU_CONFIGURATION_CHANGED));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void sessionConfigurationChanged(long sessionId) {
        outbox.inventorySkusForSession(sessionId)
                .forEach(skuId -> append(skuId, InventoryChangeReason.SKU_CONFIGURATION_CHANGED));
    }

    /** 以已提交库存事件为恢复事实；SENT只代表投递，不能替代库存已同步的证明。 */
    public Map<Long, Long> latestSignals(long afterSku, int size) {
        Map<Long, Long> signals = new LinkedHashMap<>();
        outbox.inventorySignals(afterSku, size).forEach(event -> signals.put(event.getAggregateId(), event.getId()));
        return signals;
    }

    /** 稳定事件ID使Redis预扣恢复可重复写入同一Outbox事实。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendOnce(long skuId, InventoryChangeReason reason, String eventId) {
        append(skuId, reason, eventId, true);
    }

    private void append(long skuId, InventoryChangeReason reason, String eventId) {
        append(skuId, reason, eventId, false);
    }

    private void append(long skuId, InventoryChangeReason reason, String eventId, boolean idempotent) {
        OutboxEvent event = new OutboxEvent();
        event.setEventId(eventId);
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
        if (idempotent) outbox.insertIdempotent(event);
        else outbox.insert(event);
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
