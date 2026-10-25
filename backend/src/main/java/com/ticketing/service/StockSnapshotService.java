package com.ticketing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ticketing.entity.Activity;
import com.ticketing.entity.ActivitySession;
import com.ticketing.entity.PurchaseRequest;
import com.ticketing.entity.TicketSku;
import com.ticketing.enums.ActivityStatus;
import com.ticketing.enums.SessionStatus;
import com.ticketing.enums.TicketStatus;
import com.ticketing.mapper.ActivityMapper;
import com.ticketing.mapper.ActivitySessionMapper;
import com.ticketing.mapper.PurchaseRequestMapper;
import com.ticketing.mapper.TicketSkuMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Redis库存快照只从父资源到SKU加锁，并按BUCKETED账本读取真实可售量。 */
@Service
public class StockSnapshotService {
    private final ActivityMapper activities;
    private final ActivitySessionMapper sessions;
    private final TicketSkuMapper tickets;
    private final PurchaseRequestMapper requests;
    private final TicketInventory inventory;

    public StockSnapshotService(ActivityMapper activities, ActivitySessionMapper sessions,
            TicketSkuMapper tickets, PurchaseRequestMapper requests, TicketInventory inventory) {
        this.activities = activities;
        this.sessions = sessions;
        this.tickets = tickets;
        this.requests = requests;
        this.inventory = inventory;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 30)
    public InventorySnapshot lockAndRead(long skuId) {
        TicketSku initial = tickets.selectById(skuId);
        if (initial == null) throw new IllegalStateException("Redis库存恢复发现票档不存在，保持SKU冻结");
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
        LocalDateTime now = requests.now();
        boolean eligible = TicketStatus.ON_SALE.name().equals(sku.getStatus())
                && ActivityStatus.PUBLISHED.name().equals(activity.getStatus())
                && SessionStatus.SCHEDULED.name().equals(session.getStatus())
                && !sku.getSaleStartsAt().isAfter(now) && sku.getSaleEndsAt().isAfter(now)
                && !sku.getSaleStartsAt().isBefore(session.getSaleStartsAt())
                && !sku.getSaleEndsAt().isAfter(session.getStartsAt())
                && session.getEndsAt().isAfter(now);
        return new InventorySnapshot(sku.getVersion(), available, eligible, requests.activeForSku(skuId));
    }

    public record InventorySnapshot(int version, long databaseAvailable, boolean eligible,
            List<PurchaseRequest> activeRequests) { }
}
