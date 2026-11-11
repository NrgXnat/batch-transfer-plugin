package org.nrg.xnatx.plugins.transfer.jms.tasks.services.impl;

import lombok.extern.slf4j.Slf4j;
import org.nrg.framework.orm.hibernate.AbstractHibernateEntityService;
import org.nrg.xnatx.plugins.transfer.jms.tasks.dao.BatchTransferHistoryDAO;
import org.nrg.xnatx.plugins.transfer.jms.tasks.dao.BatchTransferItemDAO;
import org.nrg.xnatx.plugins.transfer.jms.tasks.entities.BatchTransferHistory;
import org.nrg.xnatx.plugins.transfer.jms.tasks.entities.BatchTransferItem;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferItemService;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferProgressService;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferProgressService.Completion;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferProgressService.Outcome;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hibernate-backed {@link BatchTransferItemService}. Each method is one transaction that changes the item row with a
 * conditional update, counts the item on the progress row only when that update changed it, and writes the run's
 * final status when the count finished the batch: item row, then progress row, then run row, always in that order.
 */
@Slf4j
@Service
public class HibernateBatchTransferItemService
        extends AbstractHibernateEntityService<BatchTransferItem, BatchTransferItemDAO>
        implements BatchTransferItemService {

    private final BatchTransferHistoryDAO      historyDao;
    private final BatchTransferProgressService progressService;

    @Autowired
    public HibernateBatchTransferItemService(final BatchTransferHistoryDAO historyDao,
                                             final BatchTransferProgressService progressService) {
        this.historyDao = historyDao;
        this.progressService = progressService;
    }

    @Override
    @Transactional
    public StartResult start(final String trackingId, final String itemId) {
        final BatchTransferHistory run = historyDao.findByTrackingId(trackingId);
        if (run == null) {
            return new StartResult(true, null);   // submitted before history existed
        }
        if (run.getStatus() == BatchTransferHistory.Status.Stopping) {
            if (getDao().markStopped(run.getId(), itemId) == 1) {
                return new StartResult(false, count(trackingId, run.getId(), Outcome.STOPPED));
            }
        } else if (getDao().markRunning(run.getId(), itemId) == 1) {
            return new StartResult(true, null);
        }

        final BatchTransferItem.Status status = getDao().findStatus(run.getId(), itemId);
        if (status == null) {
            log.error("Batch {} has no row for item {}; running it without one.", trackingId, itemId);
            return new StartResult(true, null);
        }
        // A finished item is a repeat delivery, already counted. A running one may belong to a node that died.
        return new StartResult(!status.isFinished(), null);
    }

    @Override
    @Transactional
    public Completion finish(final String trackingId, final String itemId, final Outcome outcome,
                             final String message, final String destinationId) {
        final BatchTransferHistory run = historyDao.findByTrackingId(trackingId);
        if (run == null) {
            return progressService.recordItemDone(trackingId, outcome);   // submitted before history existed
        }
        final BatchTransferItem.Status status = outcome == Outcome.FAILED
                ? BatchTransferItem.Status.Failed : BatchTransferItem.Status.Done;
        if (getDao().finish(run.getId(), itemId, status, message, destinationId) == 0) {
            if (getDao().findStatus(run.getId(), itemId) != null) {
                return null;   // already finished and counted
            }
            log.error("Batch {} has no row for item {}; counting it without one.", trackingId, itemId);
        }
        return count(trackingId, run.getId(), outcome);
    }

    /** Counts the item, and writes the run's final status when that finished the batch. */
    private Completion count(final String trackingId, final long historyId, final Outcome outcome) {
        final Completion completion = progressService.recordItemDone(trackingId, outcome);
        if (completion != null) {
            historyDao.finish(historyId, finalStatus(completion), completion.getPassed(), completion.getFailed(),
                    completion.getStopped());
        }
        return completion;
    }

    /** Stopped when any item was stopped; otherwise by how many items failed. */
    static BatchTransferHistory.Status finalStatus(final Completion completion) {
        if (completion.getStopped() > 0) {
            return BatchTransferHistory.Status.Stopped;
        }
        if (completion.getFailed() == 0) {
            return BatchTransferHistory.Status.Completed;
        }
        return completion.getPassed() == 0 ? BatchTransferHistory.Status.Failed : BatchTransferHistory.Status.Warnings;
    }
}
