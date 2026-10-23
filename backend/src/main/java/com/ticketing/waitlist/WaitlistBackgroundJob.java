package com.ticketing.waitlist;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ticketing.waitlist.jobs.enabled", matchIfMissing = true)
public class WaitlistBackgroundJob {
    private final WaitlistCompensation compensation;

    public WaitlistBackgroundJob(WaitlistCompensation compensation) {
        this.compensation = compensation;
    }

    @Scheduled(fixedDelayString = "${ticketing.waitlist.compensation-poll-ms:60000}")
    public void compensate() {
        compensation.scan();
    }
}
