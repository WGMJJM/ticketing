package com.ticketing.waitlist;

import org.springframework.stereotype.Service;

@Service
public class WaitlistDispatcher {
    private final WaitlistMapper waitlists;
    private final WaitlistService service;
    private final WaitlistContinuationScheduler continuations;
    private final int maxSteps;

    public WaitlistDispatcher(
            WaitlistMapper waitlists, WaitlistService service, WaitlistProperties properties,
            WaitlistContinuationScheduler continuations) {
        this.waitlists = waitlists;
        this.service = service;
        this.continuations = continuations;
        this.maxSteps = properties.dispatchMaxSteps();
    }

    public int dispatch(long skuId) {
        int allocated = 0;
        boolean blocked = false;
        boolean progressed = false;
        for (int step = 0; step < maxSteps; step++) {
            var head = waitlists.head(skuId);
            if (head == null) {
                break;
            }
            var result = service.allocateHead(skuId, head.getId());
            if (result == WaitlistService.Allocation.BLOCKED) {
                blocked = true;
                break;
            }
            if (result == WaitlistService.Allocation.ALLOCATED) {
                allocated++;
                progressed = true;
            } else if (result == WaitlistService.Allocation.ADVANCED) {
                progressed = true;
            }
        }
        // 多次RETRY而没有任何状态前进时停止本地热循环，交由持久候补补偿任务恢复。
        if (!blocked && progressed && waitlists.head(skuId) != null) {
            continuations.schedule(skuId, () -> dispatch(skuId));
        }
        return allocated;
    }
}
