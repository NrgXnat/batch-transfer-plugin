package org.nrg.xnatx.plugins.transfer.jms.tasks.services;

import lombok.Value;
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
     * Atomically records one item's outcome. Returns a {@link Completion} <em>only</em> for the single call
     * that brings the batch to its total (increment + completion check + row removal happen under one
     * pessimistic lock, so exactly one caller across all nodes gets it); returns {@code null} otherwise
     * (batch not yet complete, already completed, or never registered).
     */
    Completion recordItemDone(String trackingId, boolean failed);

    /** The data the caller needs to fire a batch's terminal event, returned by the completing {@code recordItemDone}. */
    @Value
    class Completion {
        Integer userId;
        int     failed;
    }
}
