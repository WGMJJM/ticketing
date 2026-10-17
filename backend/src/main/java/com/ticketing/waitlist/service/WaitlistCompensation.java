package com.ticketing.waitlist.service;

import com.ticketing.waitlist.mapper.WaitlistMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WaitlistCompensation {
    private static final Logger LOG=LoggerFactory.getLogger(WaitlistCompensation.class);
    private final WaitlistMapper waitlists;
    private final WaitlistDispatcher dispatcher;
    private final int batchSize;
    private long cursor;
    public WaitlistCompensation(WaitlistMapper waitlists,WaitlistDispatcher dispatcher,
            @Value("${ticketing.waitlist.compensation-batch-size:100}") int batchSize) {
        if (batchSize<1 || batchSize>500) throw new IllegalArgumentException("候补补偿批量须为1至500");
        this.waitlists=waitlists; this.dispatcher=dispatcher; this.batchSize=batchSize;
    }
    public synchronized void scan() {
        var skus=waitlists.pendingSkus(cursor,batchSize);
        if (skus.isEmpty() && cursor!=0) { cursor=0; skus=waitlists.pendingSkus(0,batchSize); }
        for (Long sku:skus) {
            try { dispatcher.dispatch(sku); }
            catch (RuntimeException exception) { LOG.warn("候补补偿失败，票档 {}，下轮重试",sku); }
            cursor=sku;
        }
    }
}
