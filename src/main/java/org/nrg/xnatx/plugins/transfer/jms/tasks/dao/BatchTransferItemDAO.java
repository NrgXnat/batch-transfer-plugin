package org.nrg.xnatx.plugins.transfer.jms.tasks.dao;

import org.nrg.framework.orm.hibernate.AbstractHibernateDAO;
import org.nrg.framework.orm.hibernate.HibernateUtils;
import org.nrg.xnatx.plugins.transfer.jms.tasks.entities.BatchTransferItem;
import org.nrg.xnatx.plugins.transfer.jms.tasks.entities.BatchTransferItem.Status;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;

/**
 * A run's items. Like the run's, every status change is a conditional update that returns how many rows it
 * changed, with times from the database's clock.
 */
@Repository
public class BatchTransferItemDAO extends AbstractHibernateDAO<BatchTransferItem> {

    private static final String INSERT_SQL = "INSERT INTO xhbm_batch_transfer_item (created, timestamp, disabled, enabled, "
            + "history_id, item_id, mode, destination_project, preserve_subject_label, preserve_session_label, "
            + "destination_subject_label, destination_session_label, csv_values, row_number, source_subject_label, "
            + "source_label, status) VALUES (now(), now(), ?, true, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final int INSERT_BATCH_SIZE = 500;

    /**
     * Inserts a run's items as JDBC batches on the session's own connection, so they share the transaction that
     * inserted the run. Hibernate can't batch inserts of IDENTITY-keyed entities, and one statement per item made a
     * 5,000-item submit take seconds.
     */
    public void createAll(final long historyId, final List<BatchTransferItem> items) {
        getSession().doWork(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(INSERT_SQL)) {
                int pending = 0;
                for (final BatchTransferItem item : items) {
                    int column = 0;
                    insert.setTimestamp(++column, new Timestamp(HibernateUtils.DEFAULT_DATE.getTime()));
                    insert.setLong(++column, historyId);
                    insert.setString(++column, item.getItemId());
                    insert.setString(++column, item.getMode());
                    insert.setString(++column, item.getDestinationProject());
                    insert.setObject(++column, item.getPreserveSubjectLabel(), Types.BOOLEAN);
                    insert.setObject(++column, item.getPreserveSessionLabel(), Types.BOOLEAN);
                    insert.setString(++column, item.getDestinationSubjectLabel());
                    insert.setString(++column, item.getDestinationSessionLabel());
                    insert.setString(++column, item.getCsvValues());
                    insert.setObject(++column, item.getRowNumber(), Types.INTEGER);
                    insert.setString(++column, item.getSourceSubjectLabel());
                    insert.setString(++column, item.getSourceLabel());
                    insert.setString(++column, item.getStatus().name());
                    insert.addBatch();
                    if (++pending == INSERT_BATCH_SIZE) {
                        insert.executeBatch();
                        pending = 0;
                    }
                }
                if (pending > 0) {
                    insert.executeBatch();
                }
            }
        });
    }

    /** The item's status, or null when the run has no row for it. */
    public Status findStatus(final long historyId, final String itemId) {
        return getSession().createQuery("SELECT i.status FROM BatchTransferItem i "
                        + "WHERE i.history.id = :historyId AND i.itemId = :itemId", Status.class)
                .setParameter("historyId", historyId)
                .setParameter("itemId", itemId)
                .uniqueResult();
    }

    /** {@code Queued} to {@code Running}, stamping when it started. */
    public int markRunning(final long historyId, final String itemId) {
        return getSession().createQuery("UPDATE BatchTransferItem i SET i.status = :running, "
                        + "i.started = current_timestamp, i.timestamp = current_timestamp "
                        + "WHERE i.history.id = :historyId AND i.itemId = :itemId AND i.status = :queued")
                .setParameter("running", Status.Running)
                .setParameter("queued", Status.Queued)
                .setParameter("historyId", historyId)
                .setParameter("itemId", itemId)
                .executeUpdate();
    }

    /** {@code Queued} to {@code Stopped}: the run was stopped before the item started. */
    public int markStopped(final long historyId, final String itemId) {
        return getSession().createQuery("UPDATE BatchTransferItem i SET i.status = :stopped, "
                        + "i.finished = current_timestamp, i.timestamp = current_timestamp "
                        + "WHERE i.history.id = :historyId AND i.itemId = :itemId AND i.status = :queued")
                .setParameter("stopped", Status.Stopped)
                .setParameter("queued", Status.Queued)
                .setParameter("historyId", historyId)
                .setParameter("itemId", itemId)
                .executeUpdate();
    }

    /**
     * {@code Queued} or {@code Running} to {@code Done} or {@code Failed}. An item can fail before it starts, so a
     * queued one may finish directly. Returns 0 when the item has already finished.
     */
    public int finish(final long historyId, final String itemId, final Status status, final String message,
                      final String destinationId) {
        return getSession().createQuery("UPDATE BatchTransferItem i SET i.status = :status, i.message = :message, "
                        + "i.destinationId = :destinationId, i.finished = current_timestamp, "
                        + "i.timestamp = current_timestamp "
                        + "WHERE i.history.id = :historyId AND i.itemId = :itemId AND i.status IN (:unfinished)")
                .setParameter("status", status)
                .setParameter("message", message)
                .setParameter("destinationId", destinationId)
                .setParameter("historyId", historyId)
                .setParameter("itemId", itemId)
                .setParameterList("unfinished", Arrays.asList(Status.Queued, Status.Running))
                .executeUpdate();
    }

    /** A run's items in manifest-row order, then by subject and label. */
    public List<BatchTransferItem> listForRun(final long historyId) {
        return getSession().createQuery("FROM BatchTransferItem i WHERE i.history.id = :historyId "
                        + "ORDER BY i.rowNumber ASC NULLS LAST, i.sourceSubjectLabel, i.sourceLabel, i.itemId",
                        BatchTransferItem.class)
                .setParameter("historyId", historyId)
                .list();
    }
}
