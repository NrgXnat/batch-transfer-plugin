package org.nrg.xnatx.plugins.transfer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One resolved manifest row. Parsing fills {@code index}, the source labels, {@code csvValues}, and the
 * per-cell {@code valueErrors}/{@code valueWarnings}; the preflight resolver then sets {@code status} and, for
 * a match, {@code resolvedId}, or for {@code session_not_found}, {@code availableSessions}.
 */
@Data
@NoArgsConstructor
@JsonInclude
@JsonNaming(PropertyNamingStrategy.LowerCaseWithUnderscoresStrategy.class)
public class ManifestRow {

    /** Resolution outcomes. A matched row is transferable only if it also has no {@code valueErrors}. */
    public static final String STATUS_MATCHED           = "matched";
    public static final String STATUS_SUBJECT_NOT_FOUND = "subject_not_found";
    public static final String STATUS_SESSION_NOT_FOUND = "session_not_found";

    /** 1-based row number within the manifest's data rows (the header is not counted). */
    private int index;
    private String sourceSubjectLabel;
    private String sourceSessionLabel;

    /** Optional routing: the destination subject/session label this Reimport should land in (null = derive). */
    private String destinationSubjectLabel;
    private String destinationSessionLabel;

    private String status;
    /** The resolved experiment id when {@code status == matched}; otherwise null. */
    private String resolvedId;
    /** The subject's session labels when {@code status == session_not_found}; otherwise null. */
    private List<String> availableSessions;

    /** The row's value-column cells (non-required, non-reserved), keyed by column name. */
    private Map<String, String> csvValues;
    /** A cell outside the safe charset — blocks submit. */
    private List<String> valueErrors = new ArrayList<>();
    /** A soft issue (e.g. an empty cell for a bound placeholder) — submittable. */
    private List<String> valueWarnings = new ArrayList<>();
}
