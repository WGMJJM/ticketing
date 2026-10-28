package com.ticketing;

import com.ticketing.service.StockBucketRouter;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StockBucketRouterTest {
    @Test
    void validIdsAreDeterministicAndInvalidInputsAreRejected() {
        for (int count : new int[] {4, 8, 16}) {
            for (long id : new long[] {1, 2, 4095, 1L << 42, Long.MAX_VALUE}) {
                int bucket = StockBucketRouter.route(id, count);
                assertThat(bucket).isBetween(0, count - 1);
                assertThat(StockBucketRouter.route(id, count)).isEqualTo(bucket);
            }
        }
        for (long id : new long[] {0, -1, Long.MIN_VALUE}) {
            assertThatIllegalArgumentException().isThrownBy(() -> StockBucketRouter.route(id, 8));
        }
        for (int count : new int[] {-1, 0, 1, 2, 3, 5, 32}) {
            assertThatIllegalArgumentException().isThrownBy(() -> StockBucketRouter.route(1, count));
        }
    }

    @Test
    void sparseSnowflakeSequencesAcrossTimesAndWorkersLoseLowBitSkew() {
        for (int count : new int[] {4, 8, 16}) {
            int[] old = new int[count], mixed = new int[count];
            for (int millis = 0; millis < 10000; millis++) {
                for (int worker : new int[] {0, 1, 17, 31}) {
                    for (int sequence = 1; sequence <= 2; sequence++) {
                        long id = ((170000000000L + millis * 5L) << 22) | ((long) worker << 12) | sequence;
                        record(id, count, old, mixed);
                    }
                }
            }
            verify("sparse-80000", old, mixed);
            assertThat(maxShare(old)).isEqualTo(0.5);
            assertThat(maxShare(mixed)).isLessThan(maxShare(old) / 1.8);
        }
    }

    @Test
    void denseSameMillisecondSequencesRemainBalanced() {
        for (int count : new int[] {4, 8, 16}) {
            int[] old = new int[count], mixed = new int[count];
            for (int slice = 0; slice < 20; slice++) {
                for (int sequence = 0; sequence < 4096; sequence++) {
                    long id = ((170000050000L + slice) << 22) | ((long) (slice % 4) << 12) | sequence;
                    record(id, count, old, mixed);
                }
            }
            verify("dense-81920", old, mixed);
            assertThat(maxDeviation(old)).isZero();
        }
    }

    private static void record(long id, int count, int[] old, int[] mixed) {
        old[(int) id & (count - 1)]++;
        int bucket = StockBucketRouter.route(id, count);
        if (bucket < 0 || bucket >= count || bucket != StockBucketRouter.route(id, count)) {
            throw new AssertionError("路由范围或确定性异常");
        }
        mixed[bucket]++;
    }

    private static void verify(String family, int[] old, int[] mixed) {
        assertThat(maxDeviation(mixed)).isLessThan(0.05);
        System.out.printf("ROUTING %s buckets=%d old=%s mixed=%s oldMaxShare=%.6f newMaxShare=%.6f oldMaxDeviation=%.6f newMaxDeviation=%.6f%n",
                family, old.length, Arrays.toString(old), Arrays.toString(mixed),
                maxShare(old), maxShare(mixed), maxDeviation(old), maxDeviation(mixed));
    }

    private static double maxShare(int[] counts) {
        return Arrays.stream(counts).max().orElseThrow() / (double) Arrays.stream(counts).sum();
    }

    private static double maxDeviation(int[] counts) {
        double average = Arrays.stream(counts).average().orElseThrow();
        return Arrays.stream(counts).mapToDouble(value -> Math.abs(value - average) / average).max().orElseThrow();
    }
}
