package org.nrg.xnatx.plugins.transfer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * JSON body for the one-shot {@code POST /xapi/transfer/manifest} (the multipart variant carries the same
 * fields as form parts). The server resolves the manifest, builds requests for the matched rows, and reuses
 * the standard submit path; not-found rows are skipped and reported. {@code mode} defaults to Reimport.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude
@JsonNaming(PropertyNamingStrategy.LowerCaseWithUnderscoresStrategy.class)
public class ManifestSubmitRequest {
    private String sourceProject;
    private String destinationProject;
    private TransferMode mode;
    private String manifestCsv;
    private String anonScript;
    private boolean anonReplacePipeline;
    private String trackingId;
}
