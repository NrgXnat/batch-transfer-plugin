package org.nrg.xnatx.plugins.transfer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What this XNAT build supports for batch-transfer anonymization, plus the static limits the UI and
 * validation share. Returned by {@code GET /xapi/transfer/capabilities}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude
@JsonNaming(PropertyNamingStrategy.LowerCaseWithUnderscoresStrategy.class)
public class TransferCapabilities {
    /** True when the deployed xnat-web exposes the per-import {@code Anon-Script} importer param. */
    private boolean perImportAnon;
    private String  anonScriptDialect;
    private String  placeholderSyntax;
    /** Admin-tunable cap on manifest data rows for the synchronous preflight. Wire name: {@code manifest_max_rows}. */
    private Integer manifestMaxRows;
}
