package com.ticketing.messaging;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PurchaseCommands {
    private final OutboxMapper outbox;
    private final ObjectMapper json;
    public PurchaseCommands(OutboxMapper outbox, ObjectMapper json) { this.outbox=outbox; this.json=json; }

    @Transactional(propagation=Propagation.MANDATORY)
    public void append(long requestId) {
        OutboxEvent event=new OutboxEvent();
        event.setEventId(PurchaseCommand.eventId(requestId));
        event.setAggregateType(PurchaseCommand.AGGREGATE);
        event.setAggregateId(requestId);
        event.setEventType(PurchaseCommand.TYPE);
        event.setStatus("PENDING");
        event.setRetryCount(0);
        try { event.setPayload(json.writeValueAsString(new PurchaseCommand(event.getEventId(),Long.toString(requestId),1))); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("购票事件序列化失败",exception); }
        outbox.insert(event);
    }

    public boolean committed(PurchaseCommand command) {
        if(command.version()!=1 || command.requestId()==null || !command.requestId().matches("[1-9][0-9]{0,18}")) return false;
        long requestId;
        try { requestId=Long.parseLong(command.requestId()); }
        catch(NumberFormatException exception) { return false; }
        if(!PurchaseCommand.eventId(requestId).equals(command.eventId())) return false;
        OutboxEvent event=outbox.selectOne(new LambdaQueryWrapper<OutboxEvent>().eq(OutboxEvent::getEventId,command.eventId()));
        return event!=null && PurchaseCommand.TYPE.equals(event.getEventType())
                && PurchaseCommand.AGGREGATE.equals(event.getAggregateType()) && event.getAggregateId()==requestId;
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void recover(long requestId) { outbox.recoverPurchase(PurchaseCommand.eventId(requestId)); }

    @Transactional(propagation=Propagation.MANDATORY)
    public boolean failed(long requestId) { return outbox.purchaseFailed(PurchaseCommand.eventId(requestId)); }

    @Transactional(propagation=Propagation.MANDATORY)
    public void close(long requestId) { outbox.closePurchase(PurchaseCommand.eventId(requestId)); }
}
