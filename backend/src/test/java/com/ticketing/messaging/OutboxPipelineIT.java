package com.ticketing.messaging;

import com.ticketing.config.OutboxProperties;
import com.ticketing.entity.OutboxEvent;
import com.ticketing.service.OutboxService;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

/** 真实MySQL批量租约与Rabbit Confirm/mandatory Return，不启动消费者或后台发布器。 */
@SpringBootTest
class OutboxPipelineIT {
    private static final String EXCHANGE = "ticketing.audit.pipeline.it";
    private static final String QUEUE = EXCHANGE + ".queue";
    @Autowired JdbcTemplate db;
    @Autowired OutboxService outbox;
    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin broker;
    @Autowired MeterRegistry meters;
    final List<String> ids = new ArrayList<>();

    @BeforeEach
    void topology() {
        var exchange = new DirectExchange(EXCHANGE, true, false);
        var queue = new Queue(QUEUE, true);
        broker.declareExchange(exchange);
        broker.declareExchange(new DirectExchange(EXCHANGE + ".unrouted", true, false));
        broker.declareQueue(queue);
        broker.declareBinding(BindingBuilder.bind(queue).to(exchange).with(InventoryEvent.ROUTING_KEY));
        broker.purgeQueue(QUEUE, false);
    }

    @AfterEach
    void cleanup() {
        for (String id : ids) db.update("DELETE FROM outbox_event WHERE event_id=?", id);
        broker.purgeQueue(QUEUE, false);
    }

    void event(String type) {
        String id = "pipeline-" + UUID.randomUUID();
        ids.add(id);
        db.update("""
                INSERT INTO outbox_event(event_id,aggregate_type,aggregate_id,event_type,payload,next_retry_at)
                VALUES(?,'TICKET_SKU',1,?,'{}','2000-01-01')
                """, id, type);
    }

    @Test
    void boundedBatchGivesBothChannelsAShareAndOldTokensCannotOverwrite() {
        for (int index = 0; index < 4; index++) { event(PurchaseCommand.TYPE); event(InventoryEvent.TYPE); }
        List<OutboxEvent> first = outbox.claimBatch(4);
        assertThat(first).hasSize(4);
        assertThat(first.stream().filter(e -> PurchaseCommand.TYPE.equals(e.getEventType()))).hasSize(2);
        assertThat(first).allMatch(e -> e.getRetryCount() == 1);
        for (OutboxEvent event : first) db.update("UPDATE outbox_event SET next_retry_at='1999-01-01' WHERE id=?", event.getId());
        List<OutboxEvent> second = outbox.claimBatch(4);
        assertThat(second.stream().map(OutboxEvent::getId)).containsExactlyInAnyOrderElementsOf(first.stream().map(OutboxEvent::getId).toList());
        assertThat(second).allMatch(e -> e.getRetryCount() == 2);
        for (OutboxEvent event : first) { assertThat(outbox.sent(event)).isFalse(); assertThat(outbox.failed(event)).isFalse(); }
        for (OutboxEvent event : second) assertThat(outbox.sent(event)).isTrue();
    }

    @Test
    void concurrentBatchClaimsNeverReturnTheSameEventOrMoreThanRequested() throws Exception {
        for (int index = 0; index < 32; index++) { event(PurchaseCommand.TYPE); event(InventoryEvent.TYPE); }
        List<OutboxEvent> claims = new ArrayList<>();
        try (var threads = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<java.util.concurrent.Future<List<OutboxEvent>>>();
            for (int index = 0; index < 8; index++) futures.add(threads.submit(() -> outbox.claimBatch(8)));
            for (var future : futures) {
                var batch = future.get(15, TimeUnit.SECONDS);
                assertThat(batch.size()).isLessThanOrEqualTo(8);
                claims.addAll(batch);
            }
        }
        assertThat(new HashSet<>(claims.stream().map(OutboxEvent::getId).toList())).hasSize(claims.size());
        assertThat(claims).allMatch(e -> e.getRetryCount() == 1);
        // SKIP LOCKED可以少取，未认领记录仍保留PENDING而不丢失。
        assertThat(claims.size() + db.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_id LIKE 'pipeline-%' AND status='PENDING'", Integer.class)).isEqualTo(64);
    }

    @Test
    void realMandatoryReturnDoesNotPreventOtherChannelFromBeingMarkedSent() {
        event(PurchaseCommand.TYPE);
        event(InventoryEvent.TYPE);
        new OutboxPublisher(outbox, rabbit, EXCHANGE, EXCHANGE + ".unrouted", 120,
                new OutboxProperties(2, 5000, 30, 10, 2, 300, 2), meters).publishBatch();
        assertThat(db.queryForObject("SELECT status FROM outbox_event WHERE event_id=?", String.class, ids.get(0))).isEqualTo("PENDING");
        assertThat(db.queryForObject("SELECT status FROM outbox_event WHERE event_id=?", String.class, ids.get(1))).isEqualTo("SENT");
        assertThat(broker.getQueueInfo(QUEUE).getMessageCount()).isEqualTo(1);
        // 缺少交换机使真实代理关闭该发布通道并NACK；另一通道的健康消息仍须成功。
        event(PurchaseCommand.TYPE);
        event(InventoryEvent.TYPE);
        new OutboxPublisher(outbox, rabbit, EXCHANGE, EXCHANGE + ".missing." + UUID.randomUUID(), 120,
                new OutboxProperties(2, 5000, 30, 10, 2, 300, 2), meters).publishBatch();
        assertThat(db.queryForObject("SELECT status FROM outbox_event WHERE event_id=?", String.class, ids.get(2))).isEqualTo("PENDING");
        assertThat(db.queryForObject("SELECT status FROM outbox_event WHERE event_id=?", String.class, ids.get(3))).isEqualTo("SENT");
        assertThat(broker.getQueueInfo(QUEUE).getMessageCount()).isEqualTo(2);
    }
}
