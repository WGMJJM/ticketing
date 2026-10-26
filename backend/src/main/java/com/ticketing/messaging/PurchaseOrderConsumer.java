package com.ticketing.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.service.PurchaseOrderWorker;
import java.io.IOException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="ticketing.purchase.messaging-enabled", matchIfMissing=true)
public class PurchaseOrderConsumer {
    private final ObjectMapper json;
    private final PurchaseOrderWorker worker;
    public PurchaseOrderConsumer(ObjectMapper json, PurchaseOrderWorker worker) { this.json = json; this.worker = worker; }
    @RabbitListener(id="purchaseOrders", queues="${ticketing.purchase.queue:ticketing.purchase.orders}", containerFactory="purchaseListenerFactory")
    public void consume(Message message) {
        PurchaseCommand command;
        try { command = json.readValue(message.getBody(), PurchaseCommand.class); }
        catch (IOException exception) { throw new AmqpRejectAndDontRequeueException("购票命令格式不正确"); }
        if (command == null || command.requestId() <= 0 || command.skuId() <= 0
                || !Long.toString(command.requestId()).equals(message.getMessageProperties().getMessageId())) {
            throw new AmqpRejectAndDontRequeueException("购票命令标识不正确");
        }
        worker.process(command.requestId(), command.skuId());
        // 正常返回后 AUTO ACK；SQL 事务已经提交。有限重试/死信不代表可以释放 Redis 票额。
    }
}
