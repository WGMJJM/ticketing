package com.ticketing;

import com.ticketing.service.StockBucketRouter;
import java.lang.management.ManagementFactory;

/** 独立离线微基准，不作为单元测试执行；结果不代表端到端吞吐。 */
public final class StockBucketRouterBenchmark {
    private static volatile long sink;

    private StockBucketRouterBenchmark() {
    }

    public static void main(String[] args) {
        long[] ids = new long[1 << 15];
        for (int index = 0; index < ids.length; index++) {
            ids[index] = ((170000000000L + index / 2) << 22) | ((long) (index % 32) << 12) | (index % 2 + 1);
        }
        for (int round = 0; round < 4; round++) {
            sink = loop(ids, 3000000, false);
            sink = loop(ids, 3000000, true);
        }
        var allocation = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        allocation.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        int iterations = 10000000;
        for (int round = 0; round < 3; round++) {
            long oldStart = System.nanoTime();
            sink = loop(ids, iterations, false);
            long oldElapsed = System.nanoTime() - oldStart;
            long bytesBefore = allocation.getThreadAllocatedBytes(thread);
            long newStart = System.nanoTime();
            sink = loop(ids, iterations, true);
            long newElapsed = System.nanoTime() - newStart;
            long bytes = allocation.getThreadAllocatedBytes(thread) - bytesBefore;
            System.out.printf("ROUTE_BENCH round=%d calls=%d oldMs=%.3f newMs=%.3f oldNs=%.3f newNs=%.3f newAllocatedBytes=%d checksum=%d%n",
                    round, iterations, oldElapsed / 1000000.0, newElapsed / 1000000.0,
                    oldElapsed / (double) iterations, newElapsed / (double) iterations, bytes, sink);
        }
    }

    private static long loop(long[] ids, int iterations, boolean mixed) {
        long sum = 0;
        if (mixed) {
            for (int index = 0; index < iterations; index++) {
                sum += StockBucketRouter.route(ids[index & (ids.length - 1)], 8);
            }
        } else {
            for (int index = 0; index < iterations; index++) {
                sum += Math.floorMod(ids[index & (ids.length - 1)], 8);
            }
        }
        return sum;
    }
}
