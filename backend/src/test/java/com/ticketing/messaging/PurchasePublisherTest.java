package com.ticketing.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class PurchasePublisherTest {
    @Test
    void distinguishesSlotExhaustionFromBrokerFailureAndReturnsSlotsAfterAck() {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        List<CorrelationData> sent = new ArrayList<>();
        doAnswer(invocation -> { sent.add(invocation.getArgument(3)); return null; })
                .when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        PurchasePublisher publisher = new PurchasePublisher(rabbit, new ObjectMapper(), "purchase", 120, 2, 100, metrics, true);
        var first = publisher.publish(1, 1);
        var second = publisher.publish(2, 1);
        assertThat(publisher.publish(3, 1).join()).isFalse();
        assertThat(sent).hasSize(2);
        assertThat(metrics.get("ticketing.diagnostics.publisher.result").tag("reason", "slot_full").counter().count()).isEqualTo(1);
        sent.get(0).getFuture().complete(new CorrelationData.Confirm(true, null));
        assertThat(first.join()).isTrue();
        assertThat(publisher.publish(4, 1)).isNotDone();
        sent.get(1).getFuture().complete(new CorrelationData.Confirm(false, "test"));
        assertThat(second.join()).isFalse();
        assertThat(metrics.get("ticketing.diagnostics.publisher.result").tag("reason", "nack").counter().count()).isEqualTo(1);
    }

    @Test
    void timeoutIsObservedWithoutReleasingUnconfirmedSlotEarlyAndDefaultHasNoDiagnosticMeters() {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        PurchasePublisher publisher = new PurchasePublisher(mock(RabbitTemplate.class), new ObjectMapper(), "purchase", 120, 1, 1, metrics, true);
        assertThat(publisher.confirm(1, 1)).isFalse();
        assertThat(metrics.get("ticketing.diagnostics.publisher.result").tag("reason", "confirm_timeout").counter().count()).isEqualTo(1);
        assertThat(publisher.publish(2, 1).join()).isFalse();
        assertThat(metrics.get("ticketing.diagnostics.publisher.result").tag("reason", "slot_full").counter().count()).isEqualTo(1);
        SimpleMeterRegistry disabled = new SimpleMeterRegistry();
        new PurchasePublisher(mock(RabbitTemplate.class), new ObjectMapper(), "purchase", 120, 1, 1, disabled, false).confirm(1, 1);
        assertThat(disabled.getMeters()).isEmpty();
    }
}
