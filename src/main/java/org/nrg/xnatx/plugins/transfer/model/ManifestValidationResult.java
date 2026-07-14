package org.nrg.xnatx.plugins.transfer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * The result of parsing + resolving a manifest, returned by {@code POST /xapi/transfer/validate/manifest}.
 * A 200 is returned even when {@code requiredPresent} is false or rows are not-found — invalidity is data the
 * UI renders, not an HTTP error (only an unparseable CSV or an over-cap manifest is a 4xx).
 */
@Data
@NoArgsConstructor
@JsonInclude
@JsonNaming(PropertyNamingStrategy.LowerCaseWithUnderscoresStrategy.class)
public class ManifestValidationResult {
    private List<String> columns = new ArrayList<>();
    private boolean requiredPresent;
    private List<String> missingColumns = new ArrayList<>();
    private List<String> reservedColumns = new ArrayList<>();
    private List<String> valueColumns = new ArrayList<>();
    private int totalRows;
    private ManifestSummary summary;
    private List<ManifestRow> rows = new ArrayList<>();
    /** Present only when an {@code anon_script} was supplied. */
    private ScriptBinding scriptBinding;
}
