package com.ticketing.dto;

import com.ticketing.entity.Activity;
import com.ticketing.entity.ActivitySession;
import com.ticketing.entity.TicketSku;
import com.ticketing.enums.ActivityStatus;
import com.ticketing.enums.SaleStatus;
import com.ticketing.enums.SessionStatus;
import com.ticketing.enums.TicketStatus;

import java.time.LocalDateTime;

/** 锁定后取得的不可变销售事实，不向其他业务暴露可写库存实体。 */
public record TicketSale(
        long skuId,
        long sessionId,
        String name,
        long priceCents,
        int availableStock,
        int maxQuantityPerOrder,
        TicketStatus status,
        ActivityStatus activityStatus,
        SessionStatus sessionStatus,
        LocalDateTime saleStartsAt,
        LocalDateTime saleEndsAt,
        LocalDateTime sessionSaleStartsAt,
        LocalDateTime sessionStartsAt,
        LocalDateTime sessionEndsAt,
        String inventoryStrategy,
        int bucketCount) {

    /** 仅供分桶新建单的窗口事实使用；未读取余额时禁止对外提供库存语义。 */
    private static final int BALANCE_NOT_LOADED = -1;

    public static TicketSale from(Activity activity, ActivitySession session, TicketSku sku) {
        return from(activity, session, sku, sku.getTotalStock() - sku.getLockedStock() - sku.getSoldStock());
    }

    /** 分桶预占由条件UPDATE仲裁，不能把SKU旧计数字段当作桶的可售余额。 */
    public static TicketSale forBucketedPurchase(Activity activity, ActivitySession session, TicketSku sku) {
        return from(activity, session, sku, BALANCE_NOT_LOADED);
    }

    private static TicketSale from(Activity activity, ActivitySession session, TicketSku sku, int availableStock) {
        return new TicketSale(
                sku.getId(),
                session.getId(),
                sku.getName(),
                sku.getPriceCents(),
                availableStock,
                sku.getMaxQuantityPerOrder(),
                TicketStatus.valueOf(sku.getStatus()),
                ActivityStatus.valueOf(activity.getStatus()),
                SessionStatus.valueOf(session.getStatus()),
                sku.getSaleStartsAt(),
                sku.getSaleEndsAt(),
                session.getSaleStartsAt(),
                session.getStartsAt(),
                session.getEndsAt(), sku.getInventoryStrategy(), sku.getBucketCount());
    }

    public boolean bucketed() { return "BUCKETED".equals(inventoryStrategy); }

    public int availableStock() {
        if (availableStock == BALANCE_NOT_LOADED) {
            throw new IllegalStateException("当前销售事实未读取库存余额，只能用于分桶新建单的窗口校验");
        }
        return availableStock;
    }

    public TicketSale withAvailableStock(int available) {
        return new TicketSale(skuId,sessionId,name,priceCents,available,maxQuantityPerOrder,status,
                activityStatus,sessionStatus,saleStartsAt,saleEndsAt,sessionSaleStartsAt,
                sessionStartsAt,sessionEndsAt,inventoryStrategy,bucketCount);
    }

    public SaleStatus statusAt(LocalDateTime now) {
        int available = availableStock();
        SaleStatus eligibility = windowStatusAt(now);
        return eligibility == SaleStatus.SELLING && available == 0 ? SaleStatus.SOLD_OUT : eligibility;
    }

    /** 锁定的销售配置与时间资格；库存另由对应权威账本的条件更新仲裁。 */
    public SaleStatus windowStatusAt(LocalDateTime now) {
        if (activityStatus != ActivityStatus.PUBLISHED
                || sessionStatus != SessionStatus.SCHEDULED
                || !now.isBefore(sessionEndsAt)
                || status != TicketStatus.ON_SALE
                || saleStartsAt.isBefore(sessionSaleStartsAt)
                || saleEndsAt.isAfter(sessionStartsAt)) {
            return SaleStatus.UNAVAILABLE;
        }
        if (!now.isBefore(saleEndsAt)) {
            return SaleStatus.STOPPED;
        }
        if (now.isBefore(saleStartsAt)) {
            return SaleStatus.NOT_STARTED;
        }
        return SaleStatus.SELLING;
    }
}
