package com.ticketing.task;

import com.ticketing.config.PurchaseProperties;
import com.ticketing.mapper.PurchaseRequestMapper;
import com.ticketing.service.PurchaseProcessing;
import com.ticketing.service.RedisStockReservationService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="ticketing.purchase.jobs-enabled",matchIfMissing=true)
public class PurchaseRecoveryJob {
    private static final Logger LOG=LoggerFactory.getLogger(PurchaseRecoveryJob.class);
    private final PurchaseRequestMapper requests;
    private final PurchaseProcessing processing;
    private final PurchaseProperties properties;
    private final RedisStockReservationService stocks;
    private long cursor;
    public PurchaseRecoveryJob(PurchaseRequestMapper requests, PurchaseProcessing processing,
            PurchaseProperties properties, RedisStockReservationService stocks) {
        this.requests=requests; this.processing=processing; this.properties=properties; this.stocks=stocks;
    }
    @Scheduled(fixedDelayString="${ticketing.purchase.scan-delay-ms:1000}")
    public synchronized void scan() {
        // Redis-first预扣不一定已有MySQL申请；即使没有到期数据库请求，也必须扫描持久token索引。
        stocks.recoverBatch(properties.batchSize());
        var candidates=requests.recoverable(cursor,properties.batchSize());
        if(candidates.isEmpty()) { cursor=0;return; }
        for(long id:candidates) {
            cursor=id;
            try { processing.recover(id); }
            catch(RuntimeException exception) { LOG.warn("购票请求恢复暂未完成，请求 {}，错误类型 {}",id,exception.getClass().getSimpleName()); }
        }
        if(candidates.size()<properties.batchSize()) cursor=0;
    }
}
