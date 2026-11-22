package com.ticketing;

import com.ticketing.service.StockBucketRouter;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StockBucketRouterTest {
    @Test
    void validIdsAreDeterministicAndInvalidInputsAreRejected() {
        for (int count : new int[] {4, 8, 16, 32}) {
            for (long id : new long[] {1, 2, 4095, 1L << 42, Long.MAX_VALUE}) {
                int bucket = StockBucketRouter.route(id, count);
                assertThat(bucket).isBetween(0, count - 1);
                assertThat(StockBucketRouter.route(id, count)).isEqualTo(bucket);
            }
        }
        for (long id : new long[] {0, -1, Long.MIN_VALUE}) {
            assertThatIllegalArgumentException().isThrownBy(() -> StockBucketRouter.route(id, 8));
        }
        for (int count : new int[] {-1, 0, 1, 2, 3, 5, 31, 64}) {
            assertThatIllegalArgumentException().isThrownBy(() -> StockBucketRouter.route(1, count));
        }
    }

    @Test
    void sparseSnowflakeSequencesAreDistributedAcrossBuckets() {
        for (int count : new int[] {4, 8, 16, 32}) {
            int[] bucketSizes = new int[count];
            for (int millis = 0; millis < 10000; millis++) {
                for (int worker : new int[] {0, 1, 17, 31}) {
                    for (int sequence = 1; sequence <= 2; sequence++) {
                        long id = ((170000000000L + millis * 5L) << 22) | ((long) worker << 12) | sequence;
                        bucketSizes[StockBucketRouter.route(id, count)]++;
                    }
                }
            }
            assertThat(maxDeviation(bucketSizes)).isLessThan(0.05);
        }
    }

    private static double maxDeviation(int[] counts) {
        double average = Arrays.stream(counts).average().orElseThrow();
        return Arrays.stream(counts).mapToDouble(value -> Math.abs(value - average) / average).max().orElseThrow();
    }
}
