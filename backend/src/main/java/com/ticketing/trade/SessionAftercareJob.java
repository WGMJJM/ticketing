package com.ticketing.trade;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 游标仅用于公平扫描，可靠状态在数据库；失败单退避，重启从头补偿。 */
@Component
@ConditionalOnProperty(name = "ticketing.order.aftercare.enabled", havingValue = "true", matchIfMissing = true)
public class SessionAftercareJob {
    private static final Logger LOG = LoggerFactory.getLogger(SessionAftercareJob.class);
    private final AftercareMapper aftercare;
    private final OrderRefundService service;
    private final AftercareProperties properties;
    private long cursor;

    public SessionAftercareJob(AftercareMapper aftercare, OrderRefundService service, AftercareProperties properties) {
        this.aftercare = aftercare;
        this.service = service;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${ticketing.order.aftercare.scan-delay-ms:10000}")
    public synchronized void scan() {
        var candidates = aftercare.candidates(cursor, properties.batchSize());
        if (candidates.isEmpty()) {
            cursor = 0;
            candidates = aftercare.candidates(0, properties.batchSize());
        }
        for (var candidate : candidates) {
            try {
                service.processCancelledSession(candidate.id());
            } catch (RuntimeException exception) {
                LOG.warn("场次善后暂未完成，订单{}，异常类型{}", candidate.id(), exception.getClass().getSimpleName());
                try {
                    service.recordFailure(candidate.id(), properties.retryDelaySeconds(), properties.maxAttempts());
                } catch (RuntimeException recordingFailure) {
                    // 数据库不可用时结束本轮，避免无退避地反复认领；下轮仍从事实恢复。
                    LOG.warn("善后失败记录暂不可写入，结束本轮扫描");
                    return;
                }
            } finally {
                cursor = candidate.id();
            }
        }
    }
}
