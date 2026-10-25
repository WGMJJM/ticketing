package com.ticketing.task;

import com.ticketing.config.WaitlistProperties;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 单实例候补续跑队列；队列溢出时由持久WAITING记录和补偿扫描恢复。 */
@Component
public class WaitlistContinuationScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(WaitlistContinuationScheduler.class);
    private static final int WORKER_COUNT = 1;
    private final ThreadPoolExecutor executor;
    private final MeterRegistry meters;
    private final Set<Long> queuedSkus = ConcurrentHashMap.newKeySet();

    public WaitlistContinuationScheduler(WaitlistProperties properties, MeterRegistry meters) {
        this.meters = meters;
        this.executor = new ThreadPoolExecutor(
                WORKER_COUNT,
                WORKER_COUNT,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.continuationQueueCapacity()),
                task -> {
                    Thread thread = new Thread(task, "ticketing-waitlist-continuation");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    public void schedule(long skuId, Runnable continuation) {
        if (!queuedSkus.add(skuId)) {
            count("coalesced");
            return;
        }
        try {
            executor.execute(() -> {
                queuedSkus.remove(skuId);
                try {
                    continuation.run();
                    count("completed");
                } catch (RuntimeException exception) {
                    count("failed");
                    LOG.warn("候补续跑未完成，持久队列将在后续补偿扫描中恢复");
                }
            });
            count("scheduled");
        } catch (RejectedExecutionException exception) {
            queuedSkus.remove(skuId);
            count("rejected");
            LOG.warn("候补续跑队列已满，持久队列将在后续补偿扫描中恢复");
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void count(String outcome) {
        meters.counter("ticketing.waitlist.continuation", "outcome", outcome).increment();
    }
}
