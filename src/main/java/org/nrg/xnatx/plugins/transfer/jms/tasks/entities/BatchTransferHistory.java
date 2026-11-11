package org.nrg.xnatx.plugins.transfer.jms.tasks.entities;

import org.nrg.framework.orm.hibernate.AbstractHibernateEntity;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;
import javax.persistence.UniqueConstraint;
import java.util.Date;

/**
 * One row per batch transfer run, kept after the run finishes. Status changes are conditional updates in the
 * database (see {@code BatchTransferHistoryDAO}), so every node sees the same run state. Not cached: XNAT's
 * second-level cache is per node, and a node must never see a stale status.
 */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = "trackingId"),
       indexes = {@Index(name = "bt_history_destination_created", columnList = "destinationProject, created"),
                  @Index(name = "bt_history_source_created", columnList = "sourceProject, created"),
                  @Index(name = "bt_history_creator_created", columnList = "createdBy, created")})
public class BatchTransferHistory extends AbstractHibernateEntity {

    public enum Status {
        Queued, Running, Stopping, Completed, Warnings, Failed, Stopped;

        public boolean isFinished() {
            return this == Completed || this == Warnings || this == Failed || this == Stopped;
        }
    }

    private String  trackingId;
    private Status  status;
    private String  operation;
    private String  sourceProject;
    private String  destinationProject;
    private int     itemCount;
    private int     passedCount;
    private int     failedCount;
    private int     stoppedCount;
    private String  anonCustomScript;
    private boolean anonReplacedPipeline;
    private Boolean preserveSubjectLabel;
    private Boolean preserveSessionLabel;
    private String  stopRequestedBy;
    private Date    stopRequestedAt;
    private String  createdBy;
    private Integer userId;
    private Date    finished;

    public String getTrackingId() {
        return trackingId;
    }

    public void setTrackingId(final String trackingId) {
        this.trackingId = trackingId;
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Status getStatus() {
        return status;
    }

    public void setStatus(final Status status) {
        this.status = status;
    }

    /** {@code Share}, {@code Clone}, {@code Reimport}, or {@code Mixed} when the items differ. */
    public String getOperation() {
        return operation;
    }

    public void setOperation(final String operation) {
        this.operation = operation;
    }

    /** The project every item came from, or null when they came from several. */
    public String getSourceProject() {
        return sourceProject;
    }

    public void setSourceProject(final String sourceProject) {
        this.sourceProject = sourceProject;
    }

    /** The project every item went to, or null when they went to several. */
    public String getDestinationProject() {
        return destinationProject;
    }

    public void setDestinationProject(final String destinationProject) {
        this.destinationProject = destinationProject;
    }

    public int getItemCount() {
        return itemCount;
    }

    public void setItemCount(final int itemCount) {
        this.itemCount = itemCount;
    }

    public int getPassedCount() {
        return passedCount;
    }

    public void setPassedCount(final int passedCount) {
        this.passedCount = passedCount;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public void setFailedCount(final int failedCount) {
        this.failedCount = failedCount;
    }

    public int getStoppedCount() {
        return stoppedCount;
    }

    public void setStoppedCount(final int stoppedCount) {
        this.stoppedCount = stoppedCount;
    }

    /** The batch's custom anonymization script as submitted, before any manifest values were substituted. */
    @Column(columnDefinition = "TEXT")
    public String getAnonCustomScript() {
        return anonCustomScript;
    }

    public void setAnonCustomScript(final String anonCustomScript) {
        this.anonCustomScript = anonCustomScript;
    }

    public boolean isAnonReplacedPipeline() {
        return anonReplacedPipeline;
    }

    public void setAnonReplacedPipeline(final boolean anonReplacedPipeline) {
        this.anonReplacedPipeline = anonReplacedPipeline;
    }

    public Boolean getPreserveSubjectLabel() {
        return preserveSubjectLabel;
    }

    public void setPreserveSubjectLabel(final Boolean preserveSubjectLabel) {
        this.preserveSubjectLabel = preserveSubjectLabel;
    }

    public Boolean getPreserveSessionLabel() {
        return preserveSessionLabel;
    }

    public void setPreserveSessionLabel(final Boolean preserveSessionLabel) {
        this.preserveSessionLabel = preserveSessionLabel;
    }

    public String getStopRequestedBy() {
        return stopRequestedBy;
    }

    public void setStopRequestedBy(final String stopRequestedBy) {
        this.stopRequestedBy = stopRequestedBy;
    }

    @Temporal(TemporalType.TIMESTAMP)
    public Date getStopRequestedAt() {
        return stopRequestedAt;
    }

    public void setStopRequestedAt(final Date stopRequestedAt) {
        this.stopRequestedAt = stopRequestedAt;
    }

    /** The username of the user who submitted the run. */
    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(final String createdBy) {
        this.createdBy = createdBy;
    }

    public Integer getUserId() {
        return userId;
    }

    public void setUserId(final Integer userId) {
        this.userId = userId;
    }

    @Temporal(TemporalType.TIMESTAMP)
    public Date getFinished() {
        return finished;
    }

    public void setFinished(final Date finished) {
        this.finished = finished;
    }
}
