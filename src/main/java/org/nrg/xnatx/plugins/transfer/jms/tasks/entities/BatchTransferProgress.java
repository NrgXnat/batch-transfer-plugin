package org.nrg.xnatx.plugins.transfer.jms.tasks.entities;

import org.nrg.framework.orm.hibernate.AbstractHibernateEntity;

import javax.persistence.Entity;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

/**
 * One row per in-flight batch, tracking how many of its items have completed. Shared across nodes (unlike
 * the previous per-JVM counter), so a batch whose items are consumed on multiple nodes via a shared JMS
 * broker still reaches its total and fires exactly one terminal event. Rows are created on register and
 * deleted once the batch completes.
 */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"trackingId"}))
public class BatchTransferProgress extends AbstractHibernateEntity {

    private String  trackingId;
    private Integer userId;
    private int     total;
    private int     completed;
    private int     failed;

    public BatchTransferProgress() {
        super();
    }

    public BatchTransferProgress(final String trackingId, final Integer userId, final int total) {
        super();
        this.trackingId = trackingId;
        this.userId     = userId;
        this.total      = total;
        this.completed  = 0;
        this.failed     = 0;
    }

    public String getTrackingId() {
        return trackingId;
    }

    public void setTrackingId(final String trackingId) {
        this.trackingId = trackingId;
    }

    public Integer getUserId() {
        return userId;
    }

    public void setUserId(final Integer userId) {
        this.userId = userId;
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(final int total) {
        this.total = total;
    }

    public int getCompleted() {
        return completed;
    }

    public void setCompleted(final int completed) {
        this.completed = completed;
    }

    public int getFailed() {
        return failed;
    }

    public void setFailed(final int failed) {
        this.failed = failed;
    }
}
