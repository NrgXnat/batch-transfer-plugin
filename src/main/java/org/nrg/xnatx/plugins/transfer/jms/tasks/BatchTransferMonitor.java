package org.nrg.xnatx.plugins.transfer.jms.tasks;

import lombok.extern.slf4j.Slf4j;
import org.nrg.framework.services.NrgEventService;
import org.nrg.xnatx.plugins.transfer.event.BatchTransferEvent;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferHistoryService;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferItemService;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferItemService.StartResult;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferProgressService.Completion;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferProgressService.Outcome;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Where every item path reports to: the producer {@link #register}s a batch with its item count, and each item calls
 * {@link #startItem} before its work and {@link #finishItem} after it. Run, item and completion state live in shared
 * database rows, so a batch whose items are consumed across nodes still finishes exactly once; the item whose count
 * finishes the batch emits its single terminal event.
 *
 * <p>Never throws to an item path: a consumer that threw would have its message delivered again.
 */
@Slf4j
@Component
public class BatchTransferMonitor {

    private final NrgEventService             eventService;
    private final BatchTransferHistoryService historyService;
    private final BatchTransferItemService    itemService;

    @Autowired
    public BatchTransferMonitor(final NrgEventService eventService,
                                final BatchTransferHistoryService historyService,
                                final BatchTransferItemService itemService) {
        this.eventService   = eventService;
        this.historyService = historyService;
        this.itemService    = itemService;
    }

    /** Registered by the producer before enqueuing, with the number of items it will send. */
    public void register(final String trackingId, final Integer userId, final int total) {
        historyService.registerRun(trackingId, userId, total);
    }

    /**
     * Called before an item's work. Returns false when the item must be skipped: the run was stopped before it
     * started (it is then counted as stopped), or the item has already finished.
     */
    public boolean startItem(final String trackingId, final String itemId) {
        try {
            final StartResult result = itemService.start(trackingId, itemId);
            finishBatchIf(trackingId, result.getCompletion());
            return result.isRun();
        } catch (RuntimeException e) {
            log.error("Batch {}: could not record the start of item {}; running it.", trackingId, itemId, e);
            return true;
        }
    }

    /**
     * Records an item's outcome. Must be called once for every item that {@link #startItem} let run, and for every
     * item that fails before it starts, or the batch never finishes.
     */
    public void finishItem(final String trackingId, final String itemId, final Outcome outcome, final String message,
                           final String destinationId) {
        try {
            finishBatchIf(trackingId, itemService.finish(trackingId, itemId, outcome, message, destinationId));
        } catch (RuntimeException e) {
            log.error("Batch {}: could not record the outcome of item {}", trackingId, itemId, e);
        }
    }

    private void finishBatchIf(final String trackingId, final Completion completion) {
        if (completion == null) {
            return;
        }
        if (completion.getStopped() > 0) {
            eventService.triggerEvent(BatchTransferEvent.warn(completion.getUserId(), 100, trackingId,
                    String.format("Transfer stopped: %d done, %d failed, %d stopped", completion.getPassed(),
                            completion.getFailed(), completion.getStopped())));
        } else if (completion.getFailed() > 0) {
            eventService.triggerEvent(BatchTransferEvent.warn(completion.getUserId(), 100, trackingId,
                    String.format("Transfer Complete with %d %s. Please review this log carefully.",
                            completion.getFailed(), completion.getFailed() == 1 ? "warning" : "warnings")));
        } else {
            eventService.triggerEvent(BatchTransferEvent.complete(completion.getUserId(), trackingId, "Transfer Complete"));
        }
    }
}
