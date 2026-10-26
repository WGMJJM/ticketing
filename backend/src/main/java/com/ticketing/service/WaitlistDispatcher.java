package com.ticketing.service;

import com.ticketing.config.WaitlistProperties;
import com.ticketing.mapper.WaitlistMapper;
import org.springframework.stereotype.Service;

/** 候补逐单事务在 SKU 锁内协调 Redis 普通预扣，保持同 SKU FIFO。 */
@Service
public class WaitlistDispatcher {
    private final WaitlistMapper waitlists;
    private final WaitlistService service;
    private final StockSnapshotService inventory;
    private final int maxSteps;

    public WaitlistDispatcher(WaitlistMapper waitlists, WaitlistService service,
            StockSnapshotService inventory, WaitlistProperties properties) {
        this.waitlists = waitlists;
        this.service = service;
        this.inventory = inventory;
        this.maxSteps = properties.dispatchMaxSteps();
    }

    public int dispatch(long skuId) {
        int allocated = dispatchQueue(skuId);
        inventory.synchronize(skuId);
        return allocated;
    }

    public void dispatchIfInventoryChanged(long skuId, long inventorySequence) {
        if (inventory.needsRefresh(skuId)) dispatch(skuId);
    }

    private int dispatchQueue(long skuId) {
        int allocated = 0;
        for (int step = 0; step < maxSteps; step++) {
            var head = waitlists.head(skuId);
            if (head == null) break;
            var result = service.allocateHead(skuId, head.getId());
            if (result == WaitlistService.Allocation.BLOCKED) break;
            if (result == WaitlistService.Allocation.ALLOCATED) {
                allocated++;
            }
        }
        // 达到单次边界后由既有持久WAITING补偿扫描继续，不创建进程内续跑队列。
        return allocated;
    }
}
