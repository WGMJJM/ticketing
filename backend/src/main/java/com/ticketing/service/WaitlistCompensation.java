package com.ticketing.service;

import com.ticketing.config.WaitlistProperties;
import com.ticketing.mapper.WaitlistMapper;
import com.ticketing.messaging.InventoryEvents;

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
    private long inventoryCursor;
    private final InventoryEvents events;

    public WaitlistCompensation(
            WaitlistMapper waitlists,
            WaitlistDispatcher dispatcher,
            WaitlistProperties properties, InventoryEvents events) {
        this.waitlists = waitlists;
        this.dispatcher = dispatcher;
        this.batchSize = properties.compensationBatchSize();
        this.events = events;
    }

    public synchronized void scan() {
        var skus = waitlists.pendingSkus(cursor, batchSize);
        if (skus.isEmpty() && cursor != 0) {
            cursor = 0;
            skus = waitlists.pendingSkus(0, batchSize);
        }
        for (Long sku : skus) {
            try {
                dispatcher.dispatch(sku);
            } catch (RuntimeException exception) {
                LOG.warn("候补补偿失败，票档 {}，下轮重试", sku);
            }
            cursor = sku;
        }
        // 即使没有WAITING候补，也恢复未被Redis快照覆盖的释放事件；失败前进、回绕重试。
        var signals = events.latestSignals(inventoryCursor, batchSize);
        if (signals.isEmpty()) inventoryCursor = 0;
        for (var signal : signals.entrySet()) {
            inventoryCursor = signal.getKey();
            try {
                dispatcher.dispatchIfInventoryChanged(signal.getKey(), signal.getValue());
            } catch (RuntimeException exception) {
                LOG.warn("库存事件补偿暂未完成，票档 {}，下轮重试", signal.getKey());
            }
        }
    }
}
