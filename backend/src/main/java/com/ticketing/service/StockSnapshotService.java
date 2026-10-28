package com.ticketing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ticketing.common.RedisCommandDiagnostics;
import org.springframework.beans.factory.ObjectProvider;
import com.ticketing.entity.Activity;
import com.ticketing.entity.ActivitySession;
import com.ticketing.entity.PurchaseRequest;
import com.ticketing.entity.TicketSku;
import com.ticketing.mapper.ActivityMapper;
import com.ticketing.mapper.ActivitySessionMapper;
import com.ticketing.mapper.OutboxMapper;
import com.ticketing.mapper.PurchaseRequestMapper;
import com.ticketing.mapper.TicketSkuMapper;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 小型库存维护：父资源→SKU，暂停入口，待预扣归零后发布已提交的绝对余额。 */
@Service
public class StockSnapshotService {
    private final ActivityMapper activities;
    private final ActivitySessionMapper sessions;
    private final TicketSkuMapper tickets;
    private final PurchaseRequestMapper requests;
    private final OutboxMapper outbox;
    private final RedisStockReservationService stocks;
    private final TicketInventory inventory;
    private final RedisCommandDiagnostics diagnostics;

    public StockSnapshotService(ActivityMapper activities, ActivitySessionMapper sessions, TicketSkuMapper tickets,
            PurchaseRequestMapper requests, OutboxMapper outbox, RedisStockReservationService stocks, TicketInventory inventory,
            ObjectProvider<RedisCommandDiagnostics> diagnostics) {
        this.activities = activities; this.sessions = sessions; this.tickets = tickets;
        this.requests = requests; this.outbox = outbox; this.stocks = stocks; this.inventory = inventory;
        this.diagnostics = diagnostics.getIfAvailable();
    }

    @Transactional(isolation=Isolation.READ_COMMITTED, timeout=30)
    public boolean synchronize(long skuId) {
        TicketSku initial = tickets.selectById(skuId);
        if (initial == null) return false;
        ActivitySession parent = sessions.selectById(initial.getSessionId());
        Activity activity = activities.selectOne(new LambdaQueryWrapper<Activity>()
                .eq(Activity::getId, parent.getActivityId()).last("FOR SHARE"));
        ActivitySession session = sessions.selectOne(new LambdaQueryWrapper<ActivitySession>()
                .eq(ActivitySession::getId, parent.getId()).last("FOR SHARE"));
        TicketSku sku = tickets.selectOne(new LambdaQueryWrapper<TicketSku>().eq(TicketSku::getId, skuId).last("FOR UPDATE"));
        boolean initialized = tickets.redisInitialized(skuId);
        long pending = stocks.pause(skuId);
        if (pending >= 0 && diagnostics != null) diagnostics.inventoryPaused(skuId);
        if (pending > 0) return false;
        if (pending < 0 && initialized) throw new IllegalStateException("Redis 库存账本丢失，票档停卖并等待核对");
        if (!initialized) {
            long cursor = 0;
            List<PurchaseRequest> page;
            do {
                page = requests.history(skuId, cursor);
                for (PurchaseRequest result : page) { stocks.remember(result); cursor = result.id(); }
            } while (page.size() == 200);
        }
        boolean initializeCounter = !initialized && tickets.redisInitializedCount() == 0;
        // 已持 SKU X，与交易 SKU S 互斥，此刻各桶是同一受保护的已提交余额。
        if (sku.bucketed() && sku.getBucketCount() != 4 && sku.getBucketCount() != 8) {
            throw new IllegalStateException("新销售仅支持四桶或八桶");
        }
        inventory.balance(sku);
        boolean eligible = "PUBLISHED".equals(activity.getStatus())
                && "SCHEDULED".equals(session.getStatus()) && "ON_SALE".equals(sku.getStatus())
                && !sku.getSaleStartsAt().isBefore(session.getSaleStartsAt()) && !sku.getSaleEndsAt().isAfter(session.getStartsAt());
        stocks.publish(skuId, (long) sku.getTotalStock() - sku.getLockedStock() - sku.getSoldStock(), eligible,
                sku.getSaleStartsAt().toInstant(ZoneOffset.UTC).toEpochMilli(), sku.getSaleEndsAt().toInstant(ZoneOffset.UTC).toEpochMilli(),
                sku.getMaxQuantityPerOrder(), session.getId(), outbox.latestInventorySequence(skuId), !initialized, initializeCounter, tickets.hasWaitlist(skuId));
        if (!initialized) tickets.markRedisInitialized(skuId);
        if (diagnostics != null) diagnostics.inventoryReady(skuId);
        return true;
    }

    public List<Long> candidates(long after, int size) { return tickets.inventoryCandidates(after, size); }
    public boolean needsRefresh(long skuId) {
        return stocks.needsInventoryReconciliation(skuId, outbox.latestInventorySequence(skuId));
    }
}
