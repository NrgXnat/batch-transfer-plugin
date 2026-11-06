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
 * One manifest row, or one session resolved from it. Parsing fills {@code index}, the source labels,
 * {@code subjectRow}, {@code csvValues}, and the per-cell {@code valueErrors}/{@code valueWarnings}, and sets
 * {@code status} to {@code duplicate_session} for rows that cover the same session; the preflight resolver sets
 * every other {@code status} and, for a match, {@code resolvedId}. For Reimport the resolver replaces a matched
 * subject row with one entry per image session, each keeping the row's {@code index}.
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
    /** A found subject with nothing the operation can transfer (for Reimport, no image sessions). */
    public static final String STATUS_NO_SESSIONS       = "no_sessions";
    /** Another row covers the same session (a subject row plus a session row, or a repeated row). Blocks submit. */
    public static final String STATUS_DUPLICATE_SESSION = "duplicate_session";

    /** 1-based row number within the manifest's data rows (the header is not counted). */
    private int index;
    private String sourceSubjectLabel;
    private String sourceSessionLabel;

    /**
     * True when the manifest row left {@code source_session_label} blank, so it covers the whole subject. On an
     * entry expanded from such a row, {@code sourceSessionLabel} holds the resolved session's label.
     */
    private boolean subjectRow;

    /** Optional routing: the destination subject/session label this Reimport should land in (null = derive). */
    private String destinationSubjectLabel;
    private String destinationSessionLabel;

    private String status;
    /** The resolved experiment id when {@code status == matched}; otherwise null. */
    private String resolvedId;
    /**
     * Reimportable DICOM file count for a matched session — a display-only advisory number. Populated only
     * when {@code file_count} was requested (computed lazily, per row, on cohort-table expand); omitted from
     * the JSON otherwise.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer fileCount;

    /** The row's value-column cells (non-required, non-reserved), keyed by column name. */
    private Map<String, String> csvValues;
    /** A cell outside the safe charset — blocks submit. */
    private List<String> valueErrors = new ArrayList<>();
    /** A soft issue (e.g. an empty cell for a bound placeholder) — submittable. */
    private List<String> valueWarnings = new ArrayList<>();
}
