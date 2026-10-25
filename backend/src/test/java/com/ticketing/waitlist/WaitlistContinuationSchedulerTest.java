package com.ticketing.waitlist;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 确定性阻塞工作线程，验证有界队列溢出与同SKU合并，避免用时间猜测线程状态。 */
class WaitlistContinuationSchedulerTest {
    @Test
    void fullQueueCoalescesSameSkuAndRejectsExtraWorkWithoutBlockingCaller() throws Exception {
        var meters = new SimpleMeterRegistry();
        var scheduler = new WaitlistContinuationScheduler(new WaitlistProperties(60, 50, 100, 1), meters);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var completed = new AtomicInteger();
        try {
            scheduler.schedule(1, () -> {
                started.countDown();
                try { release.await(); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            });
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            scheduler.schedule(2, completed::incrementAndGet);
            scheduler.schedule(2, () -> completed.addAndGet(100));
            scheduler.schedule(3, () -> completed.addAndGet(1000));
            assertThat(meters.get("ticketing.waitlist.continuation").tag("outcome", "coalesced").counter().count()).isEqualTo(1);
            assertThat(meters.get("ticketing.waitlist.continuation").tag("outcome", "rejected").counter().count()).isEqualTo(1);
        } finally {
            release.countDown();
            scheduler.shutdown();
        }
        assertThat(completed.get()).isEqualTo(1);
    }

    @Test
    void failedTaskDoesNotPreventNextQueuedSkuFromRunning() {
        var meters = new SimpleMeterRegistry();
        var scheduler = new WaitlistContinuationScheduler(new WaitlistProperties(60, 50, 100, 2), meters);
        var completed = new AtomicInteger();
        scheduler.schedule(1, () -> { throw new IllegalStateException("隔离故障"); });
        scheduler.schedule(2, completed::incrementAndGet);
        scheduler.shutdown();
        assertThat(completed.get()).isEqualTo(1);
        assertThat(meters.get("ticketing.waitlist.continuation").tag("outcome", "failed").counter().count()).isEqualTo(1);
    }

    @Test
    void stoppedSchedulerRejectsNewWorkAndLeavesItToPersistentCompensation() {
        var meters = new SimpleMeterRegistry();
        var scheduler = new WaitlistContinuationScheduler(new WaitlistProperties(60, 50, 100, 1), meters);
        var completed = new AtomicInteger();
        scheduler.shutdown();
        scheduler.schedule(1, completed::incrementAndGet);
        assertThat(completed.get()).isZero();
        assertThat(meters.get("ticketing.waitlist.continuation").tag("outcome", "rejected").counter().count()).isEqualTo(1);
    }
}
