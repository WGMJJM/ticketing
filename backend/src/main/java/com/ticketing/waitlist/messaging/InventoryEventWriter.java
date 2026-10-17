package com.ticketing.waitlist.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.waitlist.entity.OutboxEvent;
import com.ticketing.waitlist.mapper.OutboxMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class InventoryEventWriter {
    public static final String TYPE = "WAITLIST_DISPATCH_REQUESTED";
    private final OutboxMapper outbox;
    private final ObjectMapper json;
    public InventoryEventWriter(OutboxMapper outbox, ObjectMapper json) { this.outbox=outbox; this.json=json; }

    @Transactional(propagation=Propagation.MANDATORY)
    public void append(long skuId, String reason) {
        OutboxEvent event = new OutboxEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setAggregateType("TICKET_SKU");
        event.setAggregateId(skuId);
        event.setEventType(TYPE);
        event.setStatus("PENDING");
        event.setRetryCount(0);
        try { event.setPayload(json.writeValueAsString(Map.of("eventId",event.getEventId(),"skuId",Long.toString(skuId),"reason",reason))); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("库存事件序列化失败",exception); }
        outbox.insert(event);
    }
}
