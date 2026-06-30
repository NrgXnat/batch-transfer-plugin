package org.nrg.xnatx.plugins.transfer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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

    //  Retain no-preserve-flag default constructor
    public TransferRequest(String destinationProject, String id, TransferMode mode) {
        this(destinationProject, id, mode, null, null);
    }
}
