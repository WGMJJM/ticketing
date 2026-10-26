package com.ticketing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ticketing.entity.Activity;
import com.ticketing.entity.ActivitySession;
import com.ticketing.entity.PurchaseRequest;
import com.ticketing.entity.TicketSku;
import com.ticketing.enums.ActivityStatus;
import com.ticketing.enums.SessionStatus;
import com.ticketing.enums.TicketStatus;
import com.ticketing.exception.BusinessException;
import com.ticketing.mapper.ActivityMapper;
import com.ticketing.mapper.ActivitySessionMapper;
import com.ticketing.mapper.PurchaseRequestMapper;
import com.ticketing.mapper.TicketSkuMapper;
import com.ticketing.mapper.OutboxMapper;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Redis库存快照按父资源到SKU加锁，读取权威余额；历史分桶票档保持停卖。 */
@Service
public class StockSnapshotService {
    private final ActivityMapper activities;
    private final ActivitySessionMapper sessions;
    private final TicketSkuMapper tickets;
    private final PurchaseRequestMapper requests;
    private final TicketInventory inventory;
    private final OutboxMapper outbox;

    public StockSnapshotService(ActivityMapper activities, ActivitySessionMapper sessions,
            TicketSkuMapper tickets, PurchaseRequestMapper requests, TicketInventory inventory, OutboxMapper outbox) {
        this.activities = activities;
        this.sessions = sessions;
        this.tickets = tickets;
        this.requests = requests;
        this.inventory = inventory;
        this.outbox = outbox;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 30)
    public InventorySnapshot lockAndRead(long skuId) {
        TicketSku initial = tickets.selectById(skuId);
        if (initial == null) throw new BusinessException(
                HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND", "票档不存在，请刷新活动页面");
        ActivitySession parent = sessions.selectById(initial.getSessionId());
        if (parent == null) throw new IllegalStateException("Redis库存恢复发现所属场次不存在，保持SKU冻结");
        Activity activity = activities.selectOne(new LambdaQueryWrapper<Activity>()
                .eq(Activity::getId, parent.getActivityId()).last("FOR SHARE"));
        ActivitySession session = sessions.selectOne(new LambdaQueryWrapper<ActivitySession>()
                .eq(ActivitySession::getId, parent.getId()).last("FOR SHARE"));
        TicketSku sku = tickets.selectOne(new LambdaQueryWrapper<TicketSku>()
                .eq(TicketSku::getId, skuId).last("FOR UPDATE"));
        if (activity == null || session == null || sku == null
                || !sku.getSessionId().equals(session.getId())
                || !session.getActivityId().equals(activity.getId())) {
            throw new IllegalStateException("Redis库存恢复发现资源关联异常，保持SKU冻结");
        }
        inventory.balance(sku);
        long available = (long) sku.getTotalStock() - sku.getLockedStock() - sku.getSoldStock();
        if (available < 0) throw new IllegalStateException("MySQL库存账本异常，保持SKU冻结");
        boolean eligible = !sku.bucketed() && TicketStatus.ON_SALE.name().equals(sku.getStatus())
                && ActivityStatus.PUBLISHED.name().equals(activity.getStatus())
                && SessionStatus.SCHEDULED.name().equals(session.getStatus())
                && !sku.getSaleStartsAt().isBefore(session.getSaleStartsAt())
                && !sku.getSaleEndsAt().isAfter(session.getStartsAt());
        return new InventorySnapshot(sku.getVersion(), available, eligible,
                sku.getSaleStartsAt().toInstant(ZoneOffset.UTC).toEpochMilli(),
                sku.getSaleEndsAt().toInstant(ZoneOffset.UTC).toEpochMilli(), outbox.latestInventorySequence(skuId),
                requests.activeForSku(skuId));
    }

    public record InventorySnapshot(int version, long databaseAvailable, boolean eligible,
            long saleStartsAt, long saleEndsAt, long inventorySequence, List<PurchaseRequest> activeRequests) { }
}
