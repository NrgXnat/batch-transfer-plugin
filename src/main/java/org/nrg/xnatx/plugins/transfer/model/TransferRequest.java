package org.nrg.xnatx.plugins.transfer.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude
@JsonNaming(PropertyNamingStrategy.LowerCaseWithUnderscoresStrategy.class)
public class TransferRequest {
    private String destinationProject;
    private String id;
    private TransferMode mode;
    // Reimport only: preserve the source subject's XNAT label by passing it to the importer
    private Boolean preserveSubjectLabel;
    // Reimport only: preserve the source session's XNAT label by passing it to the importer
    private Boolean preserveSessionLabel;
    // Reimport only: substitution values
    private Map<String, String> csvValues;
    // Reimport only: route the reimport to this destination subject/session label (importer subject/session
    // params, which override DICOM-tag-derived labels). Blank/null = derive normally.
    private String destinationSubjectLabel;
    private String destinationSessionLabel;
    // Internal threading only (the batch-level fields live on BatchTransfer): the compiled per-item script
    // and whether it replaces the destination pipeline. Not part of the per-request wire contract.
    @JsonIgnore
    private String anonScript;
    @JsonIgnore
    private boolean anonReplacePipeline;
    //  Retain no-preserve-flag default constructor
    public TransferRequest(String destinationProject, String id, TransferMode mode) {
        this(destinationProject, id, mode, null, null, null, null, null, null, false);
    }
}
