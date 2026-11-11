package org.nrg.xnatx.plugins.transfer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.Date;

/**
 * A transfer run as {@code GET /xapi/transfer/history} returns it. While a run is unfinished its counts are read
 * from its live progress; once it finishes they are its final counts.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategy.LowerCaseWithUnderscoresStrategy.class)
public class TransferRun {
    private String  trackingId;
    private String  status;
    private String  operation;
    private String  sourceProject;
    private String  destinationProject;
    private int     itemCount;
    private int     passedCount;
    private int     failedCount;
    private int     stoppedCount;
    private boolean customScript;
    private boolean anonReplacedPipeline;
    private Boolean preserveSubjectLabel;
    private Boolean preserveSessionLabel;
    private String  createdBy;
    private Date    created;
    private Date    finished;
    private String  stopRequestedBy;
    private Date    stopRequestedAt;
}
