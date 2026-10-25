package com.ticketing.task;

import com.ticketing.messaging.OutboxPublisher;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ticketing.messaging.jobs-enabled", matchIfMissing = true)
public class OutboxPublishJob {
    private final OutboxPublisher publisher;

    public OutboxPublishJob(OutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${ticketing.waitlist.outbox-poll-ms:1000}")
    public void publish() {
        publisher.publishBatch();
    }
}
