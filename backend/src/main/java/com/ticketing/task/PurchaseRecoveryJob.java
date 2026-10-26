package com.ticketing.task;

import com.ticketing.config.PurchaseProperties;
import com.ticketing.messaging.PurchasePublisher;
import com.ticketing.service.PurchaseOrderWorker;
import com.ticketing.service.RedisStockReservationService;
import com.ticketing.service.StockSnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 唯一的购票恢复入口；无处理租约、孤儿重建或自动猜测返票。 */
@Component
@ConditionalOnProperty(name="ticketing.purchase.jobs-enabled", matchIfMissing=true)
public class PurchaseRecoveryJob {
    private static final Logger LOG = LoggerFactory.getLogger(PurchaseRecoveryJob.class);
    private final RedisStockReservationService stocks;
    private final PurchaseOrderWorker worker;
    private final PurchasePublisher publisher;
    private final StockSnapshotService inventory;
    private final PurchaseProperties properties;
    private long cursor;
    public PurchaseRecoveryJob(RedisStockReservationService stocks, PurchaseOrderWorker worker,
            PurchasePublisher publisher, StockSnapshotService inventory, PurchaseProperties properties) {
        this.stocks = stocks; this.worker = worker; this.publisher = publisher; this.inventory = inventory; this.properties = properties;
    }
    @Scheduled(fixedDelayString="${ticketing.purchase.scan-delay-ms:1000}")
    public synchronized void scan() {
        try {
            for (String member : stocks.due(properties.batchSize())) {
                try {
                    stocks.defer(member);
                    String[] parts = member.split(":", 2);
                    long skuId = Long.parseLong(parts[0]); long id = Long.parseLong(parts[1]);
                    if (!worker.recover(id, skuId)) publisher.publish(id, skuId);
                } catch (RuntimeException exception) {
                    LOG.warn("购票恢复待重试，类型 {}", exception.getClass().getSimpleName());
                }
            }
            var candidates = inventory.candidates(cursor, properties.batchSize());
            if (candidates.isEmpty()) cursor = 0;
            for (long skuId : candidates) {
                cursor = skuId;
                try { if (inventory.needsRefresh(skuId)) inventory.synchronize(skuId); }
                catch (RuntimeException exception) { LOG.warn("库存待核对，SKU {}，类型 {}", skuId, exception.getClass().getSimpleName()); }
            }
        } catch (RuntimeException exception) { LOG.warn("购票恢复资源暂不可用，类型 {}", exception.getClass().getSimpleName()); }
    }
}
