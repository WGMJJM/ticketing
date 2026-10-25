package com.ticketing.messaging;

import com.ticketing.config.OutboxProperties;
import com.ticketing.entity.OutboxEvent;
import com.ticketing.service.OutboxService;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
        when(service.claimBatch(anyInt())).thenReturn(java.util.List.of(first), java.util.List.of(event(2)));
        doThrow(new AmqpException("测试代理不可用")).when(rabbit)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        publisher().publishBatch();
        verify(service, times(1)).claimBatch(anyInt());
        verify(service).failed(first);
        verify(service, never()).sent(any());
    }

    @Test
    void confirmedMessagesContinueUntilBatchIsEmpty() {
        OutboxEvent first = event(1);
        OutboxEvent next = event(2);
        when(service.claimBatch(anyInt())).thenReturn(java.util.List.of(first), java.util.List.of(next), java.util.List.of());
        doAnswer(call -> {
            call.getArgument(3, CorrelationData.class).getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        publisher().publishBatch();
        verify(service, times(3)).claimBatch(anyInt());
        verify(service).sent(first);
        verify(service).sent(next);
        verify(service, never()).failed(any());
    }

    @Test
    void interruptionPreservesFlagAndDoesNotClaimNextEvent() throws Exception {
        OutboxEvent first = event(1);
        when(service.claimBatch(anyInt())).thenReturn(java.util.List.of(first), java.util.List.of(event(2)));
        OutboxPublisher publisher = spy(publisher());
        doAnswer(call -> {
            call.getArgument(3, CorrelationData.class).getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        doThrow(new InterruptedException("测试调度中断")).when(publisher).awaitConfirm(any());
        try {
            publisher.publishBatch();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(service, times(1)).claimBatch(anyInt());
            verify(service, never()).failed(first);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void purchaseMessagesUseSeparateRouteAndFiniteBrokerLifetime() {
        OutboxEvent event = event(1);
        event.setEventType(PurchaseCommand.TYPE);
        when(service.claimBatch(anyInt())).thenReturn(java.util.List.of(event), java.util.List.of());
        doAnswer(call -> {
            assertThat(call.getArgument(0, String.class)).isEqualTo("test.purchase");
            assertThat(call.getArgument(1, String.class)).isEqualTo(PurchaseCommand.ROUTING_KEY);
            assertThat(call.getArgument(2, Message.class).getMessageProperties().getExpiration()).isEqualTo("120000");
            call.getArgument(3, CorrelationData.class).getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        publisher().publishBatch();
        verify(service).sent(event);
    }

    @Test
    void oneMissingConfirmationDoesNotBlockHealthyMessagesAndLateAckCannotMarkSent() {
        var first = event(1);
        var second = event(2);
        var third = event(3);
        when(service.claimBatch(3)).thenReturn(java.util.List.of(first, second, third));
        var confirmations = new java.util.ArrayList<CorrelationData>();
        doAnswer(call -> {
            confirmations.add(call.getArgument(3, CorrelationData.class));
            if (confirmations.size() == 3) {
                confirmations.get(2).getFuture().complete(new CorrelationData.Confirm(true, null));
                confirmations.get(1).getFuture().complete(new CorrelationData.Confirm(true, null));
            }
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        publisher(3).publishBatch();
        verify(service).sent(second);
        verify(service).sent(third);
        verify(service).failed(first);
        confirmations.getFirst().getFuture().complete(new CorrelationData.Confirm(true, null));
        verify(service, never()).sent(first);
        verify(service, times(1)).claimBatch(3);
    }

    @Test
    void mandatoryReturnAndNackAreIndependentFromHealthyAck() {
        var events = java.util.List.of(event(1), event(2), event(3));
        when(service.claimBatch(3)).thenReturn(events);
        doAnswer(call -> {
            CorrelationData correlation = call.getArgument(3);
            if (correlation.getId().startsWith("test-1:")) {
                correlation.setReturned(new org.springframework.amqp.core.ReturnedMessage(
                        call.getArgument(2), 312, "NO_ROUTE", "test.exchange", "missing"));
            }
            correlation.getFuture().complete(new CorrelationData.Confirm(!correlation.getId().startsWith("test-2:"), null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        publisher(3).publishBatch();
        verify(service).failed(events.get(0));
        verify(service).failed(events.get(1));
        verify(service).sent(events.get(2));
        verify(service, times(1)).claimBatch(3);
    }

    @Test
    void shutdownLeavesDurableLeaseForInFlightMessageWithoutClaimingAnotherWindow() {
        var first = event(1);
        when(service.claimBatch(anyInt())).thenReturn(java.util.List.of(first));
        var publisher = publisher();
        doAnswer(call -> { publisher.shutdown(); return null; }).when(rabbit)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        publisher.publishBatch();
        publisher.publishBatch();
        verify(service, times(1)).claimBatch(anyInt());
        verify(service, never()).sent(any());
        verify(service, never()).failed(any());
    }

    private OutboxPublisher publisher() { return publisher(1); }
    private OutboxPublisher publisher(int window) { return new OutboxPublisher(service, rabbit, "test.exchange", "test.purchase", 120,
            new OutboxProperties(100, 100, 30, 10, 2, 300, window), new io.micrometer.core.instrument.simple.SimpleMeterRegistry()); }
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
