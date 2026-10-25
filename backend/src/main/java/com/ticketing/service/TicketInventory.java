package com.ticketing.service;

import com.ticketing.dto.TicketSale;
import com.ticketing.entity.StockAllocation;
import com.ticketing.entity.StockBucket;
import com.ticketing.entity.TicketSku;
import com.ticketing.exception.BusinessException;
import com.ticketing.mapper.StockBucketMapper;
import com.ticketing.mapper.TicketSkuMapper;

import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.CannotAcquireLockException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** SKU权威库存的事务写入口；调用方先遵守统一用户及父级锁序。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class TicketInventory {
    private final TicketSkuMapper tickets;
    private final StockBucketMapper buckets;

    public TicketInventory(TicketSkuMapper tickets, StockBucketMapper buckets) {
        this.tickets = tickets;
        this.buckets = buckets;
    }

    /** 独立短读事务及时释放连接；不锁库存，消费者在统一锁序中最终复核。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = true)
    public int requirePurchaseEligible(long skuId) {
        Integer eligible = tickets.purchaseEligibility(skuId);
        if (eligible == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND", "票档不存在，请刷新活动页面");
        }
        if (eligible != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "TICKET_UNAVAILABLE", "票档当前不在销售中，请刷新活动页面");
        }
        Integer version = tickets.purchaseVersion(skuId);
        if (version == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND", "票档不存在，请刷新活动页面");
        }
        return version;
    }

    /** 元信息已锁定；先形成计划，再按桶号升序加锁，绝不持有高号桶回锁低号桶。 */
    public List<Allocation> reserve(TicketSale sale, int quantity, long route) {
        if (!sale.bucketed()) {
            if (tickets.reserve(sale.skuId(), quantity) != 1) throw unavailable();
            return List.of();
        }
        int primary = Math.floorMod(Long.hashCode(route), sale.bucketCount());
        StockBucket first = buckets.find(sale.skuId(), primary);
        if (first == null) throw inconsistent();
        List<Allocation> plan = new ArrayList<>();
        if (first.available() >= quantity) plan.add(new Allocation(primary, quantity));
        else {
            int remaining = quantity;
            for (StockBucket bucket : buckets.list(sale.skuId())) {
                int taken = Math.min(remaining, bucket.available());
                if (taken > 0) plan.add(new Allocation(bucket.bucketNo(), taken));
                remaining -= taken;
                if (remaining == 0) break;
            }
            if (remaining != 0) throw unavailable();
        }
        for (Allocation allocation : plan) {
            StockBucket locked = buckets.lock(sale.skuId(), allocation.bucketNo());
            if (locked == null) throw inconsistent();
            if (locked.available() < allocation.quantity()) {
                throw new CannotAcquireLockException("库存桶计划已变化，须回滚后重新选择");
            }
            requireChanged(buckets.reserve(sale.skuId(), allocation.bucketNo(), allocation.quantity()));
        }
        return List.copyOf(plan);
    }

    public void attach(long orderId, long skuId, List<Allocation> plan) {
        for (Allocation allocation : plan) {
            requireChanged(buckets.allocate(orderId, skuId, allocation.bucketNo(), allocation.quantity()));
        }
    }

    /** 必须在已有订单UPDATE锁之前调用；不修改分配记录或根据订单ID重新Hash。 */
    public void lockAllocation(TicketSale sale, long orderId, int quantity) {
        if (!sale.bucketed()) return;
        for (StockAllocation allocation : allocation(sale, orderId, quantity)) {
            if (buckets.lock(sale.skuId(), allocation.bucketNo()) == null) throw inconsistent();
        }
    }

    public void release(TicketSale sale, int quantity, long orderId) {
        if (!sale.bucketed()) requireChanged(tickets.release(sale.skuId(), quantity));
        else move(sale, orderId, quantity, Movement.RELEASE);
    }

    public void sell(TicketSale sale, int quantity, long orderId) {
        if (!sale.bucketed()) requireChanged(tickets.sell(sale.skuId(), quantity));
        else move(sale, orderId, quantity, Movement.SELL);
    }

    public void refund(TicketSale sale, int quantity, long orderId) {
        if (!sale.bucketed()) requireChanged(tickets.refund(sale.skuId(), quantity));
        else move(sale, orderId, quantity, Movement.REFUND);
    }

    private void move(TicketSale sale, long orderId, int quantity, Movement movement) {
        for (StockAllocation allocation : allocation(sale, orderId, quantity)) {
            int changed = switch (movement) {
                case SELL -> buckets.sell(sale.skuId(), allocation.bucketNo(), allocation.quantity());
                case RELEASE -> buckets.release(sale.skuId(), allocation.bucketNo(), allocation.quantity());
                case REFUND -> buckets.refund(sale.skuId(), allocation.bucketNo(), allocation.quantity());
            };
            requireChanged(changed);
        }
    }

    private List<StockAllocation> allocation(TicketSale sale, long orderId, int quantity) {
        List<StockAllocation> allocations = buckets.allocations(orderId);
        long sum = 0;
        for (StockAllocation allocation : allocations) {
            if (allocation.skuId() != sale.skuId() || allocation.quantity() < 1
                    || allocation.bucketNo() < 0 || allocation.bucketNo() >= sale.bucketCount()) throw inconsistent();
            sum = Math.addExact(sum, allocation.quantity());
        }
        if (allocations.isEmpty() || sum != quantity) throw inconsistent();
        return allocations;
    }

    /** 一条非锁定读的快照汇总；不为展示或交易合计排他锁住全部桶。 */
    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    public TicketSku balance(TicketSku sku) {
        if (!sku.bucketed()) return sku;
        List<StockBucket> rows = buckets.list(sku.getId());
        long total = 0, locked = 0, sold = 0;
        if (rows.size() != sku.getBucketCount()) throw inconsistent();
        for (int number = 0; number < rows.size(); number++) {
            StockBucket bucket = rows.get(number);
            if (bucket.bucketNo() != number) throw inconsistent();
            total = Math.addExact(total, bucket.totalStock());
            locked = Math.addExact(locked, bucket.lockedStock());
            sold = Math.addExact(sold, bucket.soldStock());
        }
        if (total != sku.getTotalStock() || locked + sold > total) throw inconsistent();
        sku.setLockedStock(Math.toIntExact(locked));
        sku.setSoldStock(Math.toIntExact(sold));
        return sku;
    }

    public TicketSale lockWaitlist(TicketSale sale) {
        if (!sale.bucketed()) return sale;
        buckets.waitlistGate(sale.skuId());
        TicketSku sku = balance(tickets.selectById(sale.skuId()));
        return sale.withAvailableStock(Math.toIntExact((long) sku.getTotalStock() - sku.getLockedStock() - sku.getSoldStock()));
    }

    /** 管理入口已持场次UPDATE，与所有交易的场次SHARE互斥；按升序只调整可用量。 */
    public void resize(TicketSku sku, int newTotal) {
        if (!sku.bucketed() || newTotal == sku.getTotalStock()) return;
        long delta = (long) newTotal - sku.getTotalStock();
        List<StockBucket> rows = buckets.list(sku.getId());
        long increase = Math.max(0, delta);
        int start = Math.floorMod(sku.getTotalStock(), sku.getBucketCount());
        for (StockBucket bucket : rows) {
            StockBucket current = buckets.lock(sku.getId(), bucket.bucketNo());
            long change = increase > 0
                    ? increase / rows.size() + (Math.floorMod(current.bucketNo() - start, rows.size()) < increase % rows.size() ? 1 : 0)
                    : -Math.min(-delta, current.available());
            if (change != 0) requireChanged(buckets.resize(sku.getId(), current.bucketNo(), Math.toIntExact(current.totalStock() + change)));
            delta -= change;
            if (delta == 0) return;
        }
        throw inconsistent();
    }

    private BusinessException unavailable() {
        return new BusinessException(HttpStatus.CONFLICT, "STOCK_UNAVAILABLE", "库存不足或销售窗口已结束，请刷新票档");
    }

    private IllegalStateException inconsistent() {
        return new IllegalStateException("库存桶或订单分配账本不一致，停止交易并人工核对");
    }

    private void requireChanged(int changed) {
        if (changed != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "STOCK_CONFLICT", "订单库存数据不一致，操作未生效");
        }
    }

    public record Allocation(int bucketNo, int quantity) { }
    private enum Movement { SELL, RELEASE, REFUND }
}
