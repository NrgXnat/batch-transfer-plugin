package org.nrg.xnatx.plugins.transfer.jms.tasks;

import lombok.extern.slf4j.Slf4j;
import org.nrg.framework.services.NrgEventService;
import org.nrg.xnatx.plugins.transfer.event.BatchTransferEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory fan-in for the JMS-parallelized Reimport batches. The producer {@link #register}s a batch
 * with its item count; each consumer reports its item's outcome via {@link #itemDone}; the consumer
 * that reports the final item emits the single terminal {@code Completed}/{@code Warning} event (the
 * one the old sequential loop emitted inline).
 *
 * <p>An in-memory counter is used rather than counting per-item workflows because Reimport's session
 * workflow is created mid-processing (inside {@code importExperiment}), so an item that fails before
 * that point would have no workflow to count — whereas {@code itemDone}, called from the consumer's
 * {@code finally}, always advances. Per-item workflows still exist (created by {@code importExperiment})
 * for audit; they just aren't the completion signal.
 *
 * <p>State is per-JVM. With XNAT's in-process ({@code vm://}) broker, a batch's producer, consumers,
 * and this registry all live in one JVM, so the count is coherent. A mid-batch server restart loses
 * the registry, so that batch's terminal event won't auto-fire (its session workflows still complete) —
 * the same durability the previous {@code ExecutorService} flow had.
 */
@Slf4j
@Component
public class BatchTransferMonitor {

    private final NrgEventService eventService;

    private final Map<String, BatchInfo> active = new ConcurrentHashMap<>();

    @Autowired
    public BatchTransferMonitor(final NrgEventService eventService) {
        this.eventService = eventService;
    }

    /** Registered by the producer before enqueuing, with the number of items it will send. */
    public void register(final String trackingId, final Integer userId, final int total) {
        active.put(trackingId, new BatchInfo(userId, total));
    }

    /** Best-effort progress percent for per-item InProgress events; clamped 0..99 (100 is terminal). */
    public int currentPercent(final String trackingId) {
        final BatchInfo info = active.get(trackingId);
        if (info == null || info.total <= 0) {
            return 0;
        }
        final int pct = (int) Math.floor((info.completed.get() * 100.0) / info.total);
        return Math.max(0, Math.min(99, pct));
    }

    /**
     * Reports one item's outcome. The consumer that brings the completed count up to the batch total
     * emits the terminal event exactly once and clears the batch. Must be called once per item on
     * every path (success or handled failure), or fan-in stalls.
     */
    public void itemDone(final String trackingId, final boolean failed) {
        final BatchInfo info = active.get(trackingId);
        if (info == null) {
            return;
        }
        if (failed) {
            info.failed.incrementAndGet();
        }
        if (info.completed.incrementAndGet() != info.total) {
            return; // not the last item
        }
        // Last item in — emit this batch's single terminal event. incrementAndGet() == total is seen
        // by exactly one thread, and every failed-increment happens-before it, so the count is exact.
        final int failures = info.failed.get();
        if (failures > 0) {
            eventService.triggerEvent(BatchTransferEvent.warn(info.userId, 100, trackingId,
                    String.format("Transfer Complete with %d %s. Please review this log carefully.",
                            failures, failures == 1 ? "warning" : "warnings")));
        } else {
            eventService.triggerEvent(BatchTransferEvent.complete(info.userId, trackingId, "Transfer Complete"));
        }
        active.remove(trackingId);
    }

    private static final class BatchInfo {
        private final Integer       userId;
        private final int           total;
        private final AtomicInteger completed = new AtomicInteger(0);
        private final AtomicInteger failed    = new AtomicInteger(0);

        private BatchInfo(final Integer userId, final int total) {
            this.userId = userId;
            this.total = total;
        }
    }
}
