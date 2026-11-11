package org.nrg.xnatx.plugins.transfer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.Date;
import java.util.Map;

/**
 * One item of a run: its request, the labels it had when the run was submitted, and its outcome. {@code csvValues}
 * is present only for users allowed to see a run's values. For a user who can't read the item's destination project,
 * the destination ID and labels are left out and {@code destinationHidden} is true, so a client can tell them from
 * values that don't exist.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategy.LowerCaseWithUnderscoresStrategy.class)
public class TransferRunItem {
    private String              itemId;
    private String              mode;
    private Integer             rowNumber;
    private String              sourceSubjectLabel;
    private String              sourceLabel;
    private String              destinationProject;
    private String              destinationSubjectLabel;
    private String              destinationSessionLabel;
    private Boolean             preserveSubjectLabel;
    private Boolean             preserveSessionLabel;
    private Map<String, String> csvValues;
    private String              status;
    private String              message;
    private String              destinationId;
    private String              destinationLabel;
    private Boolean             destinationHidden;
    private Date                started;
    private Date                finished;
}
