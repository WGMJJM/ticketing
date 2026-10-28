package com.ticketing.service;

/** 将稳定购票申请ID映射到库存桶；仅执行整数运算，不访问外部资源。 */
public final class StockBucketRouter {
    private static final long FIRST_MULTIPLIER = 0xff51afd7ed558ccdL;
    private static final long SECOND_MULTIPLIER = 0xc4ceb9fe1a85ec53L;

    private StockBucketRouter() {
    }

    public static int route(long requestId, int bucketCount) {
        if (requestId <= 0 || (bucketCount != 4 && bucketCount != 8)) {
            throw new IllegalArgumentException("requestId或库存桶数量不合法");
        }
        // MurmurHash3 64-bit finalizer：打散雪花ID的时间、节点和低位序列特征。
        long mixed = requestId;
        mixed ^= mixed >>> 33;
        mixed *= FIRST_MULTIPLIER;
        mixed ^= mixed >>> 33;
        mixed *= SECOND_MULTIPLIER;
        mixed ^= mixed >>> 33;
        return (int) mixed & (bucketCount - 1);
    }
}
