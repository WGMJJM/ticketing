package com.ticketing.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "ticketing.waitlist",
        name = {"jobs.enabled", "messaging.enabled"},
        matchIfMissing = true)
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
