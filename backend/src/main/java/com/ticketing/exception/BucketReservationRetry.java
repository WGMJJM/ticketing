package com.ticketing.exception;

/** 单桶不足后结束当前事务，重新以升序桶锁完成跨桶分配；不代表售罄。 */
public final class BucketReservationRetry extends RuntimeException {
    public BucketReservationRetry() { super("切换升序跨桶预占事务"); }
}
