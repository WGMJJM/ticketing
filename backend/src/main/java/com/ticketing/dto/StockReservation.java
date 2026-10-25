package com.ticketing.dto;

/** Lua 已持久化的抢票预扣凭据，可在申请写入前的进程故障后恢复。 */
public record StockReservation(
        byte[] token,
        long userId,
        long skuId,
        int quantity,
        String idempotencyKey,
        byte[] requestHash) { }
