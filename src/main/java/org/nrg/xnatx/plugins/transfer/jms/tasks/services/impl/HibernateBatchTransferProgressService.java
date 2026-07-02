package org.nrg.xnatx.plugins.transfer.jms.tasks.services.impl;

import org.nrg.framework.orm.hibernate.AbstractHibernateEntityService;
import org.nrg.xnatx.plugins.transfer.jms.tasks.dao.BatchTransferProgressDAO;
import org.nrg.xnatx.plugins.transfer.jms.tasks.entities.BatchTransferProgress;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferProgressService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hibernate-backed {@link BatchTransferProgressService}. The atomic increment in {@link #recordItemDone}
 * reads the row under a pessimistic write lock, so concurrent callers across nodes serialize and exactly
 * one observes {@code completed == total} — the one that then fires the terminal event.
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
    public BatchTransferProgress recordItemDone(final String trackingId, final boolean failed) {
        final BatchTransferProgress row = getDao().findByTrackingIdForUpdate(trackingId);
        if (row == null) {
            return null;
        }
        row.setCompleted(row.getCompleted() + 1);
        if (failed) {
            row.setFailed(row.getFailed() + 1);
        }
        getDao().update(row);
        return row;
    }

    @Override
    @Transactional
    public void remove(final String trackingId) {
        final BatchTransferProgress row = getDao().findByTrackingId(trackingId);
        if (row != null) {
            getDao().delete(row);
        }
    }

    @Override
    @Transactional
    public int currentPercent(final String trackingId) {
        final BatchTransferProgress row = getDao().findByTrackingId(trackingId);
        if (row == null || row.getTotal() <= 0) {
            return 0;
        }
        final int pct = (int) Math.floor((row.getCompleted() * 100.0) / row.getTotal());
        return Math.max(0, Math.min(99, pct));
    }
}
