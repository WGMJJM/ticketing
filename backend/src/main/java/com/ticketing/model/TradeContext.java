package com.ticketing.model;


/** 仅用于同一事务内的资源协作；不能缓存或跨请求复用。 */
public record TradeContext(long userId, boolean active, boolean admin, TicketSale sale) {}
