package com.ticketing.service;

import com.ticketing.config.WaitlistProperties;
import com.ticketing.mapper.WaitlistMapper;
import com.ticketing.task.WaitlistContinuationScheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 冻结Redis普通预扣余额后，按MySQL队首顺序逐单分配候补。 */
@Service
public class WaitlistDispatcher {
    private final WaitlistMapper waitlists;
    private final WaitlistService service;
    private final RedisStockReservationService stocks;
    private final int maxSteps;

    @Autowired
    public WaitlistDispatcher(WaitlistMapper waitlists, WaitlistService service,
            RedisStockReservationService stocks, WaitlistProperties properties) {
        this.waitlists = waitlists;
        this.service = service;
        this.stocks = stocks;
        this.maxSteps = properties.dispatchMaxSteps();
    }

    /** 保留测试夹具的旧构造形态；生产Spring上下文使用带库存仲裁器的构造器。 */
    public WaitlistDispatcher(WaitlistMapper waitlists, WaitlistService service, WaitlistProperties properties,
            WaitlistContinuationScheduler ignored) {
        this.waitlists = waitlists;
        this.service = service;
        this.stocks = null;
        this.maxSteps = properties.dispatchMaxSteps();
    }

    public int dispatch(long skuId) {
        if (stocks == null) return dispatchQueue(skuId, Integer.MAX_VALUE);
        return stocks.dispatchFrozen(skuId, budget -> dispatchQueue(skuId, budget));
    }

    private int dispatchQueue(long skuId, int availableBudget) {
        int allocated = 0;
        int remaining = availableBudget;
        for (int step = 0; step < maxSteps; step++) {
            var head = waitlists.head(skuId);
            if (head == null) break;
            var result = service.allocateHead(skuId, head.getId(), remaining);
            if (result == WaitlistService.Allocation.BLOCKED) break;
            if (result == WaitlistService.Allocation.ALLOCATED) {
                allocated++;
                remaining -= head.getQuantity();
            }
        }
        // 达到单次边界后由既有持久WAITING补偿扫描继续，不创建进程内续跑队列。
        return allocated;
    }
}
