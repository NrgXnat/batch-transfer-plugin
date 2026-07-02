package org.nrg.xnatx.plugins.transfer.jms.tasks.services;

import org.nrg.framework.orm.hibernate.BaseHibernateService;
import org.nrg.xnatx.plugins.transfer.jms.tasks.entities.BatchTransferProgress;

/**
 * Cross-node completion counter for batch transfers. Backed by a shared DB row per batch, so a batch whose
 * items are consumed on more than one node still reaches its total and fires exactly one terminal event.
 */
public interface BatchTransferProgressService extends BaseHibernateService<BatchTransferProgress> {

    /** Registers a batch with its item count, resetting any stale row left by a prior crash. */
    void register(String trackingId, Integer userId, int total);

    /**
     * Atomically records one item's outcome and returns the batch's post-increment row, or {@code null} if
     * no such batch exists (already completed and removed, or never registered).
     */
    BatchTransferProgress recordItemDone(String trackingId, boolean failed);

    /** Removes a completed batch's row. */
    void remove(String trackingId);

    /** Best-effort progress percent; clamped 0..99 (100 is terminal). 0 if unknown. */
    int currentPercent(String trackingId);
}
