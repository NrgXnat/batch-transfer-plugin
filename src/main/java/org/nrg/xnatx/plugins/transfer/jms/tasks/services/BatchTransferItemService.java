package org.nrg.xnatx.plugins.transfer.jms.tasks.services;

import lombok.Value;
import org.nrg.framework.orm.hibernate.BaseHibernateService;
import org.nrg.xnatx.plugins.transfer.jms.tasks.entities.BatchTransferItem;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferProgressService.Completion;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferProgressService.Outcome;

/**
 * The item lifecycle. Each item is counted on the batch's progress row exactly once, by the update that gives it
 * its final status, and that update, the count and (for the last item) the run's final status share one
 * transaction. Locks are always taken item row first, then progress row, then run row.
 */
public interface BatchTransferItemService extends BaseHibernateService<BatchTransferItem> {

    /**
     * Called before an item's work. The item runs unless the run is stopping (the item becomes {@code Stopped} and
     * is counted) or the item has already finished (a repeat delivery, not counted again). An item still
     * {@code Running} runs again, since the node that started it may have died. An item with no row runs.
     */
    StartResult start(String trackingId, String itemId);

    /**
     * Records an item's outcome ({@link Outcome#DONE} or {@link Outcome#FAILED}) and counts it. Returns the batch's
     * {@link Completion} when this item finished it, else null. An item that has already finished isn't counted
     * again; an item with no row is counted, as before history existed.
     */
    Completion finish(String trackingId, String itemId, Outcome outcome, String message, String destinationId);

    /** Whether the item should run, and the batch's completion when stopping the item finished the batch. */
    @Value
    class StartResult {
        boolean    run;
        Completion completion;
    }
}
