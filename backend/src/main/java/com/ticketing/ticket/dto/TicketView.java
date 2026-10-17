package com.ticketing.ticket.dto;

import com.ticketing.ticket.entity.TicketSku;
import com.ticketing.ticket.model.SaleStatus;
import com.ticketing.ticket.model.TicketStatus;
import java.time.Instant;
import java.time.ZoneOffset;

public record TicketView(String id, String sessionId, String name, String priceCents, int totalStock,
                         int lockedStock, int soldStock, int availableStock, Instant saleStartsAt,
                         Instant saleEndsAt, int maxQuantityPerOrder, TicketStatus status,
                         SaleStatus saleStatus, Instant serverTime, int version) {
    public static TicketView from(TicketSku sku, SaleStatus saleStatus, Instant now) {
        return new TicketView(sku.getId().toString(), sku.getSessionId().toString(), sku.getName(),
                sku.getPriceCents().toString(), sku.getTotalStock(), sku.getLockedStock(), sku.getSoldStock(),
                sku.getTotalStock() - sku.getLockedStock() - sku.getSoldStock(),
                sku.getSaleStartsAt().toInstant(ZoneOffset.UTC), sku.getSaleEndsAt().toInstant(ZoneOffset.UTC),
                sku.getMaxQuantityPerOrder(), TicketStatus.valueOf(sku.getStatus()), saleStatus, now, sku.getVersion());
    }
}
