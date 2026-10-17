package com.ticketing.waitlist.service;

import com.ticketing.waitlist.mapper.WaitlistMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class WaitlistDispatcher {
    private final WaitlistMapper waitlists;
    private final WaitlistService service;
    private final int maxSteps;
    public WaitlistDispatcher(WaitlistMapper waitlists,WaitlistService service,
            @Value("${ticketing.waitlist.dispatch-max-steps:50}") int maxSteps) {
        if (maxSteps<1 || maxSteps>500) throw new IllegalArgumentException("单次候补调度步数须为1至500");
        this.waitlists=waitlists; this.service=service; this.maxSteps=maxSteps;
    }
    public int dispatch(long skuId) {
        int allocated=0;
        for (int step=0;step<maxSteps;step++) {
            var head=waitlists.head(skuId);
            if (head==null) break;
            var result=service.allocateHead(skuId,head.getId());
            if (result==WaitlistService.Allocation.BLOCKED) break;
            if (result==WaitlistService.Allocation.ALLOCATED) allocated++;
        }
        return allocated;
    }
}
