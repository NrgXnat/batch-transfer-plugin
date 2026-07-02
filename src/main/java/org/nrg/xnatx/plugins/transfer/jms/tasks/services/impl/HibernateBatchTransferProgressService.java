package org.nrg.xnatx.plugins.transfer.jms.tasks.services.impl;

import org.nrg.framework.orm.hibernate.AbstractHibernateEntityService;
import org.nrg.xnatx.plugins.transfer.jms.tasks.dao.BatchTransferProgressDAO;
import org.nrg.xnatx.plugins.transfer.jms.tasks.entities.BatchTransferProgress;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferProgressService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hibernate-backed {@link BatchTransferProgressService}. {@link #recordItemDone} does the increment,
 * completion check, and row removal under a single pessimistic write lock, so concurrent callers across
 * nodes serialize and exactly one is told it completed the batch.
 *
 * <p>{@link #register}'s insert relies on the {@code trackingId} unique constraint to arbitrate a
 * cross-node race; the losing insert fails at transaction commit and is caught by the producer
 * ({@code BatchTransferServiceImpl.batchTransfer}), whose peer already created the equivalent row.
 */
@Service
public class HibernateBatchTransferProgressService
        extends AbstractHibernateEntityService<BatchTransferProgress, BatchTransferProgressDAO>
        implements BatchTransferProgressService {

    @Override
    @Transactional
    public void register(final String trackingId, final Integer userId, final int total) {
        final BatchTransferProgress existing = getDao().findByTrackingId(trackingId);
        if (existing == null) {
            create(new BatchTransferProgress(trackingId, userId, total));
        } else {
            // Reset a stale row (e.g. a tracking-id reused after a crash) to a clean start.
            existing.setUserId(userId);
            existing.setTotal(total);
            existing.setCompleted(0);
            existing.setFailed(0);
            getDao().update(existing);
        }
    }

    @Override
    @Transactional
    public Completion recordItemDone(final String trackingId, final boolean failed) {
        final BatchTransferProgress row = getDao().findByTrackingIdForUpdate(trackingId);
        if (row == null) {
            return null;
        }
        row.setCompleted(row.getCompleted() + 1);
        if (failed) {
            row.setFailed(row.getFailed() + 1);
        }
        if (row.getCompleted() >= row.getTotal()) {
            // This call completed the batch. Delete under the same lock so an over-count (JMS redelivery)
            // finds no row and cannot re-fire the terminal event; >= (not ==) so an over-count can't skip it.
            final Completion completion = new Completion(row.getUserId(), row.getFailed());
            getDao().delete(row);
            return completion;
        }
        getDao().update(row);
        return null;
    }
}
