package com.ticketing.task;

import com.ticketing.service.ActivityPopularService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ActivityPopularRankingJob {
    private final ActivityPopularService popular;

    public ActivityPopularRankingJob(ActivityPopularService popular) {
        this.popular = popular;
    }

    @Scheduled(fixedDelayString = "${ticketing.activity-popular.refresh-ms:300000}")
    public void refresh() {
        popular.refresh();
    }
}
