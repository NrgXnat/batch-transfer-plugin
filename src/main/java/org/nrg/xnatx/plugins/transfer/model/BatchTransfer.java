package org.nrg.xnatx.plugins.transfer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude
@JsonNaming(PropertyNamingStrategy.LowerCaseWithUnderscoresStrategy.class)
public class BatchTransfer {
    List<TransferRequest> requests;
    String trackingId;

    /**
     * Optional, REIMPORT-only. A DicomEdit script applied per session at ingest (xnat-web's
     * {@code Anon-Script} importer param). When it carries {@code ${csv.*}} placeholders, each request's
     * {@code csvValues} are substituted in. Null = no custom anonymization (current behavior).
     * Wire name: {@code anon_script}.
     */
    String anonScript;

    /**
     * Optional. When true the custom script replaces the destination project's anonymization (the plugin
     * also sends {@code PREVENT_ANON}); default false = additive (script runs in addition to the
     * destination pipeline). Wire name: {@code anon_replace_pipeline}.
     */
    boolean anonReplacePipeline;

    /** Backward-compatible constructor for the (requests, trackingId) form. */
    public BatchTransfer(final List<TransferRequest> requests, final String trackingId) {
        this.requests = requests;
        this.trackingId = trackingId;
    }
}
