package com.ticketing.waitlist.service;

import com.ticketing.waitlist.messaging.OutboxPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="ticketing.waitlist.jobs.enabled",matchIfMissing=true)
public class WaitlistBackgroundJob {
    private final OutboxPublisher publisher;
    private final WaitlistCompensation compensation;
    private final boolean messaging;
    public WaitlistBackgroundJob(OutboxPublisher publisher,WaitlistCompensation compensation,
            @Value("${ticketing.waitlist.messaging.enabled:true}") boolean messaging) {
        this.publisher=publisher; this.compensation=compensation; this.messaging=messaging;
    }
    @Scheduled(fixedDelayString="${ticketing.waitlist.outbox-poll-ms:1000}")
    public void publish() { if (messaging) publisher.publishBatch(); }
    @Scheduled(fixedDelayString="${ticketing.waitlist.compensation-poll-ms:60000}")
    public void compensate() { compensation.scan(); }
}
