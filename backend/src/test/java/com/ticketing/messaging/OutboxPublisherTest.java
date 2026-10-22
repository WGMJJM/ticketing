package com.ticketing.messaging;

import com.ticketing.config.OutboxProperties;
import com.ticketing.service.OutboxService;

import com.ticketing.entity.OutboxEvent;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPublisherTest {
    private final OutboxService service = mock(OutboxService.class);
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);

    @Test
    void brokerFailureStopsBatchWithoutClaimingMoreEvents() {
        OutboxEvent first = event(1);
        when(service.claimNext()).thenReturn(first, event(2));
        doThrow(new AmqpException("测试代理不可用")).when(rabbit)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        publisher().publishBatch();
        verify(service, times(1)).claimNext();
        verify(service).failed(first);
        verify(service, never()).sent(any());
    }

    @Test
    void confirmedMessagesContinueUntilBatchIsEmpty() {
        OutboxEvent first = event(1);
        OutboxEvent next = event(2);
        when(service.claimNext()).thenReturn(first, next, null);
        doAnswer(call -> {
            call.getArgument(3, CorrelationData.class).getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        publisher().publishBatch();
        verify(service, times(3)).claimNext();
        verify(service).sent(first);
        verify(service).sent(next);
        verify(service, never()).failed(any());
    }

    @Test
    void interruptionPreservesFlagAndDoesNotClaimNextEvent() throws Exception {
        OutboxEvent first = event(1);
        when(service.claimNext()).thenReturn(first, event(2));
        OutboxPublisher publisher = spy(publisher());
        doThrow(new InterruptedException("测试调度中断")).when(publisher).awaitConfirm(any());
        try {
            publisher.publishBatch();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(service, times(1)).claimNext();
            verify(service).failed(first);
        } finally {
            Thread.interrupted();
        }
    }

    private OutboxPublisher publisher() { return new OutboxPublisher(service, rabbit, "test.exchange", new OutboxProperties(100, 100, 30, 10, 2, 300)); }
    private OutboxEvent event(long id) {
        OutboxEvent event = new OutboxEvent();
        event.setId(id);
        event.setEventId("test-" + id);
        event.setStatus("PROCESSING");
        event.setRetryCount(1);
        event.setPayload("{}");
        return event;
    }
}
