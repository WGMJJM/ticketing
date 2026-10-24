package com.ticketing.inventory;

/** 单桶数据库快照；字段采用有符号整数，汇总使用long并精确缩窄。 */
public record StockBucket(long skuId, int bucketNo, int totalStock, int lockedStock, int soldStock) {
    public int available() {
        return Math.toIntExact((long) totalStock - lockedStock - soldStock);
    }
}
