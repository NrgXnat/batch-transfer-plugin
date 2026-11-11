package org.nrg.xnatx.plugins.transfer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-manifest resolution tally. {@code matched} counts sessions (a subject row counts once per session it
 * expanded to). The other counts are manifest rows: {@code not_found} (subject or session not found),
 * {@code no_sessions} and {@code duplicate_session}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude
@JsonNaming(PropertyNamingStrategy.LowerCaseWithUnderscoresStrategy.class)
public class ManifestSummary {
    private int matched;
    private int notFound;
    private int noSessions;
    private int duplicateSession;

    public ManifestSummary(final int matched, final int notFound) {
        this(matched, notFound, 0, 0);
    }
}
