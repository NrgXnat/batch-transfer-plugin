package org.nrg.xnatx.plugins.transfer.jms.tasks.services;

import org.nrg.framework.orm.hibernate.BaseHibernateService;
import org.nrg.xft.security.UserI;
import org.nrg.xnatx.plugins.transfer.jms.tasks.entities.BatchTransferHistory;
import org.nrg.xnatx.plugins.transfer.jms.tasks.entities.BatchTransferItem;
import org.nrg.xnatx.plugins.transfer.model.BatchTransfer;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Transfer runs: recording a submit, the run lifecycle, Stop, and reading runs back. */
public interface BatchTransferHistoryService extends BaseHibernateService<BatchTransferHistory> {

    enum StopResult { STOPPING, ALREADY_STOPPING, FINISHED, NOT_FOUND }

    /**
     * Inserts the run and one row per item, all {@code Queued}, in one transaction that commits before the call
     * returns, so a consumer on any node finds the rows. Throws {@link BatchTransferRunExistsException} when the
     * batch's tracking id already has a run.
     */
    void recordSubmit(BatchTransfer batch, UserI user);

    /** Registers the batch's progress row and moves its run from {@code Queued} to {@code Running}. */
    void registerRun(String trackingId, Integer userId, int total);

    /** Moves a {@code Queued} or {@code Running} run to {@code Stopping}. */
    StopResult requestStop(String trackingId, String username);

    BatchTransferHistory findByTrackingId(String trackingId);

    /** See {@code BatchTransferHistoryDAO#list}. */
    List<BatchTransferHistory> list(String project, String createdBy, BatchTransferHistory.Status status,
                                    String visibleTo, Collection<String> readableProjects, int offset, int limit);

    long count(String project, String createdBy, BatchTransferHistory.Status status, String visibleTo,
               Collection<String> readableProjects);

    List<BatchTransferItem> items(long historyId);

    /** The current labels of the given subjects and experiments, by ID. */
    Map<String, String> itemLabels(Collection<String> ids);
}
