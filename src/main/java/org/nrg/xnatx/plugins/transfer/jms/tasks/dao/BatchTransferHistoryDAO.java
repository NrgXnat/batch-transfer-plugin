package org.nrg.xnatx.plugins.transfer.jms.tasks.dao;

import org.hibernate.query.Query;
import org.nrg.framework.orm.hibernate.AbstractHibernateDAO;
import org.nrg.xnatx.plugins.transfer.jms.tasks.entities.BatchTransferHistory;
import org.nrg.xnatx.plugins.transfer.jms.tasks.entities.BatchTransferHistory.Status;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Runs. Every status change is a conditional update that returns how many rows it changed, so two nodes deciding
 * at once can't both win, and times come from the database's clock rather than either node's.
 */
@Repository
public class BatchTransferHistoryDAO extends AbstractHibernateDAO<BatchTransferHistory> {

    private static final List<Status> UNFINISHED = Arrays.asList(Status.Queued, Status.Running, Status.Stopping);

    public BatchTransferHistory findByTrackingId(final String trackingId) {
        return getSession().createQuery("FROM BatchTransferHistory h WHERE h.trackingId = :trackingId",
                        BatchTransferHistory.class)
                .setParameter("trackingId", trackingId)
                .uniqueResult();
    }

    /** {@code Queued} to {@code Running}. Never overwrites {@code Stopping}. */
    public int markRunning(final String trackingId) {
        return getSession().createQuery("UPDATE BatchTransferHistory h SET h.status = :running, "
                        + "h.timestamp = current_timestamp WHERE h.trackingId = :trackingId AND h.status = :queued")
                .setParameter("running", Status.Running)
                .setParameter("queued", Status.Queued)
                .setParameter("trackingId", trackingId)
                .executeUpdate();
    }

    /** {@code Queued} or {@code Running} to {@code Stopping}, recording who asked and when. */
    public int requestStop(final String trackingId, final String username) {
        return getSession().createQuery("UPDATE BatchTransferHistory h SET h.status = :stopping, "
                        + "h.stopRequestedBy = :username, h.stopRequestedAt = current_timestamp, "
                        + "h.timestamp = current_timestamp "
                        + "WHERE h.trackingId = :trackingId AND h.status IN (:from)")
                .setParameter("stopping", Status.Stopping)
                .setParameter("username", username)
                .setParameter("trackingId", trackingId)
                .setParameterList("from", Arrays.asList(Status.Queued, Status.Running))
                .executeUpdate();
    }

    /** Writes a run's final status and counts, unless it has already finished. */
    public int finish(final long historyId, final Status status, final int passed, final int failed, final int stopped) {
        return getSession().createQuery("UPDATE BatchTransferHistory h SET h.status = :status, "
                        + "h.passedCount = :passed, h.failedCount = :failed, h.stoppedCount = :stopped, "
                        + "h.finished = current_timestamp, h.timestamp = current_timestamp "
                        + "WHERE h.id = :id AND h.status IN (:unfinished)")
                .setParameter("status", status)
                .setParameter("passed", passed)
                .setParameter("failed", failed)
                .setParameter("stopped", stopped)
                .setParameter("id", historyId)
                .setParameterList("unfinished", UNFINISHED)
                .executeUpdate();
    }

    /**
     * A page of runs, newest first. {@code project} matches the source or the destination; null means any.
     * {@code createdBy} limits the runs to one user's; null means anyone's. When {@code visibleTo} is set, only runs
     * that user started, or whose source project is in {@code readableProjects}, are returned.
     */
    public List<BatchTransferHistory> list(final String project, final String createdBy, final Status status,
                                           final String visibleTo, final Collection<String> readableProjects,
                                           final int offset, final int limit) {
        final Query<BatchTransferHistory> query = getSession().createQuery(
                "FROM BatchTransferHistory h" + where(project, createdBy, status, visibleTo, readableProjects)
                        + " ORDER BY h.created DESC, h.id DESC", BatchTransferHistory.class);
        bind(query, project, createdBy, status, visibleTo, readableProjects);
        return query.setFirstResult(offset).setMaxResults(limit).list();
    }

    public long count(final String project, final String createdBy, final Status status, final String visibleTo,
                      final Collection<String> readableProjects) {
        final Query<Long> query = getSession().createQuery("SELECT count(h) FROM BatchTransferHistory h"
                + where(project, createdBy, status, visibleTo, readableProjects), Long.class);
        bind(query, project, createdBy, status, visibleTo, readableProjects);
        return query.uniqueResult();
    }

    private static String where(final String project, final String createdBy, final Status status,
                                final String visibleTo, final Collection<String> readableProjects) {
        final StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (project != null) {
            where.append(" AND (h.sourceProject = :project OR h.destinationProject = :project)");
        }
        if (createdBy != null) {
            where.append(" AND h.createdBy = :createdBy");
        }
        if (status != null) {
            where.append(" AND h.status = :status");
        }
        if (visibleTo != null) {
            where.append(readableProjects == null || readableProjects.isEmpty()
                    ? " AND h.createdBy = :visibleTo"
                    : " AND (h.createdBy = :visibleTo OR h.sourceProject IN (:readable))");
        }
        return where.toString();
    }

    private static void bind(final Query<?> query, final String project, final String createdBy, final Status status,
                             final String visibleTo, final Collection<String> readableProjects) {
        if (project != null) {
            query.setParameter("project", project);
        }
        if (createdBy != null) {
            query.setParameter("createdBy", createdBy);
        }
        if (status != null) {
            query.setParameter("status", status);
        }
        if (visibleTo != null) {
            query.setParameter("visibleTo", visibleTo);
            if (readableProjects != null && !readableProjects.isEmpty()) {
                query.setParameterList("readable", readableProjects);
            }
        }
    }
}
