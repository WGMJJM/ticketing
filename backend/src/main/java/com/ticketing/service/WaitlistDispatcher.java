package com.ticketing.service;

import com.ticketing.config.WaitlistProperties;
import com.ticketing.mapper.WaitlistMapper;

import org.springframework.stereotype.Service;

@Service
public class WaitlistDispatcher {
    private final WaitlistMapper waitlists;
    private final WaitlistService service;
    private final int maxSteps;

    public WaitlistDispatcher(
            WaitlistMapper waitlists, WaitlistService service, WaitlistProperties properties) {
        this.waitlists = waitlists;
        this.service = service;
        this.maxSteps = properties.dispatchMaxSteps();
    }

    public int dispatch(long skuId) {
        int allocated = 0;
        for (int step = 0; step < maxSteps; step++) {
            var head = waitlists.head(skuId);
            if (head == null) {
                break;
            }
            var result = service.allocateHead(skuId, head.getId());
            if (result == WaitlistService.Allocation.BLOCKED) {
                break;
            }
            if (result == WaitlistService.Allocation.ALLOCATED) {
                allocated++;
            }
        }
        return allocated;
    }
}
