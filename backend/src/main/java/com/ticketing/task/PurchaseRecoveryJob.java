package com.ticketing.task;

import com.ticketing.config.PurchaseProperties;
import com.ticketing.mapper.PurchaseRequestMapper;
import com.ticketing.service.PurchaseProcessing;

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
    private long cursor;
    public PurchaseRecoveryJob(PurchaseRequestMapper requests,PurchaseProcessing processing,PurchaseProperties properties) { this.requests=requests;this.processing=processing;this.properties=properties; }
    @Scheduled(fixedDelayString="${ticketing.purchase.scan-delay-ms:1000}")
    public synchronized void scan() {
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
