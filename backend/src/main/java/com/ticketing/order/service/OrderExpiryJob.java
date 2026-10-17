package com.ticketing.order.service;

import com.ticketing.order.entity.TicketOrder;
import com.ticketing.order.mapper.OrderMapper;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ticketing.order.expiry.enabled", matchIfMissing = true)
public class OrderExpiryJob {
    private static final Logger LOG = LoggerFactory.getLogger(OrderExpiryJob.class);
    private final OrderMapper orders;
    private final OrderService service;
    private final int batchSize;
    private LocalDateTime lastExpiry;
    private long lastId;

    public OrderExpiryJob(OrderMapper orders, OrderService service,
            @Value("${ticketing.order.expiry.batch-size:100}") int batchSize) {
        if (batchSize < 1 || batchSize > 500) throw new IllegalArgumentException("超时扫描批量须为1至500");
        this.orders = orders;
        this.service = service;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${ticketing.order.expiry.scan-delay-ms:30000}")
    public synchronized void closeExpiredOrders() {
        // 扫描不锁订单；真正处理必须先取得用户锁，不倒置第四阶段的锁序。
        var candidates = orders.expiredCandidates(batchSize, lastExpiry, lastId);
        if (candidates.isEmpty() && lastExpiry != null) {
            lastExpiry = null;
            lastId = 0;
            candidates = orders.expiredCandidates(batchSize, null, 0);
        }
        for (TicketOrder candidate : candidates) {
            try { service.expire(candidate.getUserId(), candidate.getId()); }
            catch (RuntimeException exception) {
                LOG.warn("超时关单失败，订单编号 {}，下次扫描重试", candidate.getId(), exception);
            }
            // 失败也前进，避免批量为1时一笔坏数据阻塞其余订单；扫描到尾后回绕重试。
            lastExpiry = candidate.getExpiresAt();
            lastId = candidate.getId();
        }
    }
}
