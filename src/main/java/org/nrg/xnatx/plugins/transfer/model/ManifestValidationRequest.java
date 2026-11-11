package org.nrg.xnatx.plugins.transfer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * JSON body for {@code POST /xapi/transfer/validate/manifest} (the multipart variant carries the same fields
 * as form parts). {@code manifestCsv} is the raw CSV text; {@code anonScript} is optional and, when present,
 * drives {@code script_binding} + empty-cell warnings. {@code mode} is accepted and ignored: a manifest always
 * resolves for Reimport.
 * {@code fileCount} (wire name {@code file_count}) is an opt-in flag: when true, matched rows also carry a
 * reimportable-DICOM {@code file_count} — a filesystem walk per session, so it is off by default and requested
 * only for the rows a cohort table expands.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude
@JsonNaming(PropertyNamingStrategy.LowerCaseWithUnderscoresStrategy.class)
public class ManifestValidationRequest {
    private String sourceProject;
    private TransferMode mode;
    private String manifestCsv;
    private String anonScript;
    private boolean fileCount;
}
