package org.nrg.xnatx.plugins.transfer.jms.tasks;

import lombok.extern.slf4j.Slf4j;
import org.nrg.framework.services.NrgEventService;
import org.nrg.xnatx.plugins.transfer.event.BatchTransferEvent;
import org.nrg.xnatx.plugins.transfer.jms.tasks.entities.BatchTransferProgress;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferProgressService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Fan-in for JMS-parallelized batches: the producer {@link #register}s a batch with its item count, each
 * consumer reports via {@link #itemDone}, and the one that brings the count up to the total emits the
 * single terminal {@code Completed}/{@code Warning} event. Completion state lives in a shared DB row (see
 * {@link BatchTransferProgressService}), not in memory, so a batch whose items are consumed across nodes
 * (shared JMS broker) still reaches its total and fires exactly one terminal event. A counter is used
 * rather than counting per-item workflows because Reimport creates its workflow mid-processing, so an item
 * that fails earlier would have none — whereas {@code itemDone} (called from the consumer's
 * {@code finally}) always advances.
 */
@Slf4j
@Component
public class BatchTransferMonitor {

    private final NrgEventService              eventService;
    private final BatchTransferProgressService progressService;

    @Autowired
    public BatchTransferMonitor(final NrgEventService eventService,
                                final BatchTransferProgressService progressService) {
        this.eventService    = eventService;
        this.progressService = progressService;
    }

    /** Registered by the producer before enqueuing, with the number of items it will send. */
    public void register(final String trackingId, final Integer userId, final int total) {
        progressService.register(trackingId, userId, total);
    }

    /** Best-effort progress percent for per-item InProgress events; clamped 0..99 (100 is terminal). */
    public int currentPercent(final String trackingId) {
        return progressService.currentPercent(trackingId);
    }

    /**
     * Reports one item's outcome. The consumer (on any node) that brings the completed count up to the
     * batch total emits the terminal event exactly once and clears the batch. Must be called once per item
     * on every path (success or handled failure), or fan-in stalls.
     */
    public void itemDone(final String trackingId, final boolean failed) {
        final BatchTransferProgress progress = progressService.recordItemDone(trackingId, failed);
        if (progress == null || progress.getCompleted() != progress.getTotal()) {
            return; // unknown batch, or not the last item
        }
        // Last item in — fires once (the pessimistic lock lets one caller across all nodes see == total).
        if (progress.getFailed() > 0) {
            eventService.triggerEvent(BatchTransferEvent.warn(progress.getUserId(), 100, trackingId,
                    String.format("Transfer Complete with %d %s. Please review this log carefully.",
                            progress.getFailed(), progress.getFailed() == 1 ? "warning" : "warnings")));
        } else {
            eventService.triggerEvent(BatchTransferEvent.complete(progress.getUserId(), trackingId, "Transfer Complete"));
        }
        progressService.remove(trackingId);
    }
}
