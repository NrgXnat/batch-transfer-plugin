package org.nrg.xnatx.plugins.transfer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Result of a one-shot manifest submit: the batch tracking id, how many items were enqueued, and the manifest
 * rows skipped. {@code skippedNotFound} is the total number of skipped rows; {@code skipped} breaks it down by
 * reason ({@code subject_not_found}, {@code session_not_found}, {@code no_sessions}).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude
@JsonNaming(PropertyNamingStrategy.LowerCaseWithUnderscoresStrategy.class)
public class ManifestSubmitResult {
    private String trackingId;
    private int itemCount;
    private int skippedNotFound;
    private Map<String, Integer> skipped;
}
