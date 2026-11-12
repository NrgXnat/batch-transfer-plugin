package org.nrg.xnatx.plugins.transfer.jms.tasks.services;

import org.nrg.framework.orm.hibernate.BaseHibernateService;
import org.nrg.xft.security.UserI;
import org.nrg.xnatx.plugins.transfer.jms.tasks.entities.BatchTransferHistory;
import org.nrg.xnatx.plugins.transfer.jms.tasks.entities.BatchTransferItem;
import org.nrg.xnatx.plugins.transfer.model.BatchTransfer;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** Transfer runs: recording a submit, the run lifecycle, Stop, and reading runs back. */
public interface BatchTransferHistoryService extends BaseHibernateService<BatchTransferHistory> {

    enum StopResult { STOPPING, ALREADY_STOPPING, FINISHED, NOT_FOUND }

    /**
     * Inserts the run and one row per item, all {@code Queued}, in one transaction that commits before the call
     * returns, so a consumer on any node finds the rows. Throws {@link BatchTransferRunExistsException} when the
     * batch's tracking id already has a run.
     *
     * <p>Labels are recorded only for items the user can read: those in, or shared into, one of
     * {@code readableProjects} (null means every project). Any other item is recorded as one XNAT doesn't know:
     * without labels, and not counted toward the run's source project.
     */
    void recordSubmit(BatchTransfer batch, UserI user, Collection<String> readableProjects);

    /** Registers the batch's progress row and moves its run from {@code Queued} to {@code Running}. */
    void registerRun(String trackingId, Integer userId, int total);

    /**
     * Ends a run that could not start: its unfinished items get {@code message} and become {@code Failed}, and so
     * does the run. A run already {@code Stopping} ends {@code Stopped} instead, with its items. For a batch whose
     * {@link #registerRun} failed, which has no progress row to count its items.
     */
    void failRun(String trackingId, String message);

    /** Moves a {@code Queued} or {@code Running} run to {@code Stopping}. */
    StopResult requestStop(String trackingId, String username);

    BatchTransferHistory findByTrackingId(String trackingId);

    /** See {@code BatchTransferHistoryDAO#list}. */
    List<BatchTransferHistory> list(String project, String createdBy, BatchTransferHistory.Status status,
                                    String visibleTo, Collection<String> readableProjects, int offset, int limit);

    long count(String project, String createdBy, BatchTransferHistory.Status status, String visibleTo,
               Collection<String> readableProjects);

    List<BatchTransferItem> items(long historyId);

    /** A run's items whose status changed at or after {@code since}, in the same order as {@link #items}. */
    List<BatchTransferItem> itemsChangedSince(long historyId, Date since);

    /** The current labels of the given subjects and experiments, by ID. */
    Map<String, String> itemLabels(Collection<String> ids);
}
