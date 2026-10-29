package com.ticketing.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class PurchasePublisherTest {
    @Test
    void sendsPersistentCommandWithoutWaitingForAConfirm() throws Exception {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        ObjectMapper json = new ObjectMapper();
        var publisher = new PurchasePublisher(rabbit, json, "purchase", 120, new SimpleMeterRegistry());
        publisher.publish(12, 34);
        var sent = ArgumentCaptor.forClass(Message.class);
        verify(rabbit).send(org.mockito.ArgumentMatchers.eq("purchase"),
                org.mockito.ArgumentMatchers.eq(PurchaseCommand.ROUTING_KEY), sent.capture());
        assertThat(sent.getValue().getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(sent.getValue().getMessageProperties().getMessageId()).isEqualTo("12");
        assertThat(sent.getValue().getMessageProperties().getExpiration()).isEqualTo("120000");
        assertThat(json.readValue(sent.getValue().getBody(), PurchaseCommand.class)).isEqualTo(new PurchaseCommand(12, 34));
    }

    @Test
    void sendFailureIsObservedWithoutTurningTheAcceptedRequestIntoFailure() {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        var metrics = new SimpleMeterRegistry();
        doThrow(new AmqpException("offline")).when(rabbit).send(anyString(), anyString(), any(Message.class));
        new PurchasePublisher(rabbit, new ObjectMapper(), "purchase", 120, metrics).publish(12, 34);
        assertThat(metrics.get("ticketing.purchase.publish.failure").counter().count()).isEqualTo(1);
    }
}
