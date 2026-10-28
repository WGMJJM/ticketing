package com.ticketing.service;

import com.ticketing.dto.TicketSale;
import com.ticketing.entity.StockAllocation;
import com.ticketing.entity.StockBucket;
import com.ticketing.entity.TicketSku;
import com.ticketing.exception.BusinessException;
import com.ticketing.mapper.StockBucketMapper;
import com.ticketing.mapper.TicketSkuMapper;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import com.ticketing.exception.BucketReservationRetry;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    /** 无申请标识的旧内部入口只允许 SINGLE，不伪造分桶路由标识。 */
    public void reserve(TicketSale sale, int quantity) {
        reserve(sale, quantity, null, false);
    }

    public Map<Integer, Integer> reserve(TicketSale sale, int quantity, Long requestId, boolean ordered) {
        if (!sale.bucketed()) {
            if (tickets.reserve(sale.skuId(), quantity) != 1) throw unavailable();
            return Map.of();
        }
        int bucketCount = sale.bucketCount();
        if ((bucketCount != 4 && bucketCount != 8) || requestId == null) throw inconsistent();
        int initial = Math.floorMod(requestId, bucketCount);
        if (!ordered) {
            if (buckets.reserve(sale.skuId(), initial, quantity) == 1) return Map.of(initial, quantity);
            // UPDATE 即使影响零行也可能持锁；必须回滚整笔事务后才能回绕到更小编号。
            throw new BucketReservationRetry();
        }
        Map<Integer, Integer> allocation = new TreeMap<>();
        int remaining = quantity;
        for (int number = 0; number < bucketCount && remaining > 0; number++) {
            StockBucket bucket = buckets.lock(sale.skuId(), number);
            if (bucket == null || bucket.available() < 0) throw inconsistent();
            int taken = Math.min(remaining, bucket.available());
            if (taken > 0) {
                requireChanged(buckets.reserve(sale.skuId(), number, taken));
                allocation.put(number, taken);
                remaining -= taken;
            }
        }
        // Redis 已预扣而 MySQL 权威量不足属于账本矛盾，保留申请、暂停核对，不终结返票。
        if (remaining != 0) throw inconsistent();
        return Map.copyOf(allocation);
    }

    public void allocate(TicketSale sale, long orderId, Map<Integer, Integer> allocation) {
        for (var entry : new TreeMap<>(allocation).entrySet()) {
            requireChanged(buckets.allocate(orderId, sale.skuId(), entry.getKey(), entry.getValue()));
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
            if (bucket.bucketNo() != number || bucket.totalStock() < 0 || bucket.lockedStock() < 0
                    || bucket.soldStock() < 0 || bucket.available() < 0) throw inconsistent();
            total = Math.addExact(total, bucket.totalStock());
            locked = Math.addExact(locked, bucket.lockedStock());
            sold = Math.addExact(sold, bucket.soldStock());
        }
        if (total != sku.getTotalStock() || locked + sold > total) throw inconsistent();
        sku.setLockedStock(Math.toIntExact(locked));
        sku.setSoldStock(Math.toIntExact(sold));
        return sku;
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

    private enum Movement { SELL, RELEASE, REFUND }
}
