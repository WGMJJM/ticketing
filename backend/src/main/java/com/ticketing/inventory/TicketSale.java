package com.ticketing.inventory;

import com.ticketing.activity.ActivityStatus;
import com.ticketing.activity.SessionStatus;

import com.ticketing.activity.Activity;
import com.ticketing.activity.ActivitySession;

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
        LocalDateTime sessionEndsAt) {

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
                session.getEndsAt());
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
