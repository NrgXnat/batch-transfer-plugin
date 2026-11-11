package org.nrg.xnatx.plugins.transfer.jms.tasks.entities;

import org.nrg.framework.orm.hibernate.AbstractHibernateEntity;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;
import javax.persistence.UniqueConstraint;
import java.util.Date;

/**
 * One row per item of a run: the item's complete request and its outcome. A run can hold thousands of items, so
 * {@link BatchTransferHistory} has no collection of them; they are read with a query. The unique key on
 * ({@code history_id}, {@code itemId}) also indexes the foreign key. Not cached, for the same reason as the run.
 */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"history_id", "itemId"}))
public class BatchTransferItem extends AbstractHibernateEntity {

    public enum Status {
        Queued, Running, Done, Failed, Stopped;

        public boolean isFinished() {
            return this == Done || this == Failed || this == Stopped;
        }
    }

    private BatchTransferHistory history;
    private String               itemId;
    private String               mode;
    private String               destinationProject;
    private Boolean              preserveSubjectLabel;
    private Boolean              preserveSessionLabel;
    private String               destinationSubjectLabel;
    private String               destinationSessionLabel;
    private String               csvValues;
    private Integer              rowNumber;
    private String               sourceSubjectLabel;
    private String               sourceLabel;
    private Status               status;
    private String               message;
    private String               destinationId;
    private Date                 started;
    private Date                 finished;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "history_id", nullable = false)
    public BatchTransferHistory getHistory() {
        return history;
    }

    public void setHistory(final BatchTransferHistory history) {
        this.history = history;
    }

    /** The source item's XNAT ID. */
    @Column(nullable = false)
    public String getItemId() {
        return itemId;
    }

    public void setItemId(final String itemId) {
        this.itemId = itemId;
    }

    /** {@code Share}, {@code Clone} or {@code Reimport}. */
    public String getMode() {
        return mode;
    }

    public void setMode(final String mode) {
        this.mode = mode;
    }

    public String getDestinationProject() {
        return destinationProject;
    }

    public void setDestinationProject(final String destinationProject) {
        this.destinationProject = destinationProject;
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

    public String getDestinationSubjectLabel() {
        return destinationSubjectLabel;
    }

    public void setDestinationSubjectLabel(final String destinationSubjectLabel) {
        this.destinationSubjectLabel = destinationSubjectLabel;
    }

    public String getDestinationSessionLabel() {
        return destinationSessionLabel;
    }

    public void setDestinationSessionLabel(final String destinationSessionLabel) {
        this.destinationSessionLabel = destinationSessionLabel;
    }

    /** The item's {@code ${csv.*}} values as a JSON object, in the manifest's column order. */
    @Column(columnDefinition = "TEXT")
    public String getCsvValues() {
        return csvValues;
    }

    public void setCsvValues(final String csvValues) {
        this.csvValues = csvValues;
    }

    /** The manifest row the item came from, numbered as a spreadsheet shows the file. */
    public Integer getRowNumber() {
        return rowNumber;
    }

    public void setRowNumber(final Integer rowNumber) {
        this.rowNumber = rowNumber;
    }

    /** The source subject's label when the run was submitted. */
    public String getSourceSubjectLabel() {
        return sourceSubjectLabel;
    }

    public void setSourceSubjectLabel(final String sourceSubjectLabel) {
        this.sourceSubjectLabel = sourceSubjectLabel;
    }

    /** The source item's own label when the run was submitted. */
    public String getSourceLabel() {
        return sourceLabel;
    }

    public void setSourceLabel(final String sourceLabel) {
        this.sourceLabel = sourceLabel;
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Status getStatus() {
        return status;
    }

    public void setStatus(final Status status) {
        this.status = status;
    }

    @Column(columnDefinition = "TEXT")
    public String getMessage() {
        return message;
    }

    public void setMessage(final String message) {
        this.message = message;
    }

    /** What the item became in the destination: the shared item itself, the clone, or the reimported session. */
    public String getDestinationId() {
        return destinationId;
    }

    public void setDestinationId(final String destinationId) {
        this.destinationId = destinationId;
    }

    @Temporal(TemporalType.TIMESTAMP)
    public Date getStarted() {
        return started;
    }

    public void setStarted(final Date started) {
        this.started = started;
    }

    @Temporal(TemporalType.TIMESTAMP)
    public Date getFinished() {
        return finished;
    }

    public void setFinished(final Date finished) {
        this.finished = finished;
    }
}
