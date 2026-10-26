package com.ticketing.service;

import com.ticketing.config.WaitlistProperties;
import com.ticketing.mapper.WaitlistMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class WaitlistCompensation {
    private static final Logger LOG = LoggerFactory.getLogger(WaitlistCompensation.class);
    private final WaitlistMapper waitlists;
    private final WaitlistDispatcher dispatcher;
    private final int batchSize;
    private long cursor;

    public WaitlistCompensation(
            WaitlistMapper waitlists,
            WaitlistDispatcher dispatcher,
            WaitlistProperties properties) {
        this.waitlists = waitlists;
        this.dispatcher = dispatcher;
        this.batchSize = properties.compensationBatchSize();
    }

    public synchronized void scan() {
        var skus = waitlists.recoverySkus(cursor, batchSize);
        if (skus.isEmpty() && cursor != 0) {
            cursor = 0;
            skus = waitlists.recoverySkus(0, batchSize);
        }
        for (Long sku : skus) {
            try {
                dispatcher.dispatch(sku);
            } catch (RuntimeException exception) {
                LOG.warn("候补补偿失败，票档 {}，下轮重试", sku);
            }
            cursor = sku;
        }
    }
}
