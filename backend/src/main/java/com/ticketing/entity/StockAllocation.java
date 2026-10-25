package com.ticketing.entity;

/** 订单创建事务持久化的不可变库存归属，终态释放不得重新路由。 */
public record StockAllocation(long orderId, long skuId, int bucketNo, int quantity) { }
