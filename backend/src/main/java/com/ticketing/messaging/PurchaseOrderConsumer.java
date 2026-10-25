package com.ticketing.messaging;

import com.ticketing.service.PurchaseOrderWorker;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="ticketing.purchase.messaging-enabled",matchIfMissing=true)
public class PurchaseOrderConsumer {
    private final ObjectMapper json;
    private final PurchaseCommands commands;
    private final PurchaseOrderWorker worker;
    public PurchaseOrderConsumer(ObjectMapper json,PurchaseCommands commands,PurchaseOrderWorker worker) { this.json=json;this.commands=commands;this.worker=worker; }
    @RabbitListener(id="purchaseOrders",queues="${ticketing.purchase.queue:ticketing.purchase.orders}",
            containerFactory="purchaseListenerFactory")
    public void consume(Message message) {
        PurchaseCommand command;
        try { command=json.readValue(message.getBody(),PurchaseCommand.class); }
        catch(IOException exception) { throw new AmqpRejectAndDontRequeueException("购票命令格式不正确"); }
        if(command==null || !commands.committed(command) || !command.eventId().equals(message.getMessageProperties().getMessageId()))
            throw new AmqpRejectAndDontRequeueException("购票命令与已提交事实不一致");
        String deliveryKey = message.getMessageProperties().getCorrelationId();
        String expectedPrefix = command.eventId() + ":";
        // 关联头只供本机诊断；不把任意外来头（可能含秘密）写入JFR，也不作为业务授权事实。
        if (deliveryKey == null || !deliveryKey.startsWith(expectedPrefix)
                || !deliveryKey.substring(expectedPrefix.length()).matches("[1-9][0-9]{0,9}")) deliveryKey = null;
        worker.process(Long.parseLong(command.requestId()), deliveryKey);
        // AUTO在方法正常返回后ACK；worker的本地事务此前已提交，ACK丢失由数据库终态幂等。
    }
}
