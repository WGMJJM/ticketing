package com.ticketing.checkin.service;

import com.ticketing.checkin.mapper.ElectronicTicketMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 仅显式配置启用，逐单独立事务；发现异常保留现有票券，不删除重建。 */
@Component
@ConditionalOnProperty(name = "ticketing.checkin.backfill.enabled", havingValue = "true")
public class TicketBackfillJob {
    private static final Logger LOG = LoggerFactory.getLogger(TicketBackfillJob.class);
    private final ElectronicTicketMapper tickets;
    private final ElectronicTicketService service;
    private final int batchSize;
    private long cursor;
    public TicketBackfillJob(ElectronicTicketMapper tickets, ElectronicTicketService service,
            @Value("${ticketing.checkin.backfill.batch-size:100}") int batchSize) {
        if (batchSize < 1 || batchSize > 500) throw new IllegalArgumentException("历史补票批量须为1至500");
        this.tickets = tickets;
        this.service = service;
        this.batchSize = batchSize;
    }
    @Scheduled(fixedDelayString = "${ticketing.checkin.backfill.scan-delay-ms:30000}")
    public synchronized void scan() {
        var candidates = tickets.paidCandidates(cursor, batchSize);
        if (candidates.isEmpty() && cursor != 0) { cursor = 0; candidates = tickets.paidCandidates(0, batchSize); }
        for (var candidate : candidates) {
            try {
                int created = service.backfill(candidate.getId());
                if (created > 0) LOG.info("历史订单补票完成，订单 {}，新增 {} 张", candidate.getId(), created);
            } catch (RuntimeException exception) { LOG.warn("历史补票未完成，订单 {}，请检查数量、关联及密钥版本", candidate.getId()); }
            cursor = candidate.getId();
        }
    }
}
