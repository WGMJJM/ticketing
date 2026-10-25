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

    public static TicketSale from(Activity activity, ActivitySession session, TicketSku sku) {
        return new TicketSale(
                sku.getId(),
                session.getId(),
                sku.getName(),
                sku.getPriceCents(),
                sku.getTotalStock() - sku.getLockedStock() - sku.getSoldStock(),
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

    public TicketSale withAvailableStock(int available) {
        return new TicketSale(skuId,sessionId,name,priceCents,available,maxQuantityPerOrder,status,
                activityStatus,sessionStatus,saleStartsAt,saleEndsAt,sessionSaleStartsAt,
                sessionStartsAt,sessionEndsAt,inventoryStrategy,bucketCount);
    }

    public SaleStatus statusAt(LocalDateTime now) {
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
        return availableStock == 0 ? SaleStatus.SOLD_OUT : SaleStatus.SELLING;
    }
}
