package org.nrg.xnatx.plugins.transfer.service;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.lang3.StringUtils;
import org.nrg.xnatx.plugins.transfer.model.ManifestRow;
import org.nrg.xnatx.plugins.transfer.model.ManifestSummary;
import org.nrg.xnatx.plugins.transfer.model.ManifestValidationResult;
import org.nrg.xnatx.plugins.transfer.model.ScriptBinding;
import org.nrg.xnatx.plugins.transfer.model.TransferMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses a manifest CSV and classifies its columns, with per-cell value checks and (optionally)
 * {@code ${csv.*}} binding. Pure text work — no XNAT involvement, so it is unit-testable without XDAT/Spring;
 * per-row resolution is the {@link PreflightResolver}'s job, run by the API after this.
 *
 * <p>Column roles:
 * <ul>
 *   <li><b>Required</b> columns {@value #COL_SUBJECT} + {@value #COL_SESSION} identify the session. A blank
 *   {@value #COL_SESSION} cell makes the row a subject row, which covers every session of the subject; the
 *   column itself stays required.</li>
 *   <li><b>Reserved</b> columns are {@code _}-prefixed (notes; never exposed to a script).</li>
 *   <li><b>Value</b> columns are the rest — the {@code ${csv.*}}-eligible set.</li>
 * </ul>
 * A malformed CSV is a 400 and an over-cap manifest a 413 (both {@link ManifestValidationException}); a
 * <i>parsed</i> manifest that merely lacks required columns comes back as a 200 result the UI renders.
 */
@Service
public class ManifestService {

    public static final String COL_SUBJECT      = "source_subject_label";
    public static final String COL_SESSION      = "source_session_label";
    public static final String COL_DEST_SUBJECT = "destination_subject_label";
    public static final String COL_DEST_SESSION = "destination_session_label";

    /** Characters XNAT keeps verbatim in a label — alphanumeric start, then alphanumerics and {@code _ -}.
     *  XNAT's {@code OmUtils.cleanValue} rewrites everything else (incl. space and {@code .}) to {@code _} at
     *  ingest, so restricting routing labels to this set makes them round-trip unchanged; it is also a subset
     *  of the {@code ${csv.*}} DICOM-safe charset, so a routing-valid value is always substitution-valid. */
    private static final Pattern LABEL_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]*$");

    private final BatchTransferPolicy policy;
    private final ScriptCompiler scriptCompiler;

    @Autowired
    public ManifestService(final BatchTransferPolicy policy, final ScriptCompiler scriptCompiler) {
        this.policy = policy;
        this.scriptCompiler = scriptCompiler;
    }

    /**
     * Parse and classify {@code rawCsv}, filling every field of the result except each row's resolution
     * ({@code status}/{@code resolvedId}) and the summary tally, which the caller
     * completes after running the {@link PreflightResolver}. {@code mode} (null = Reimport) decides what a subject
     * row overlaps with: for Reimport it covers all of the subject's sessions; for Share/Clone only the subject.
     *
     * <p>Blank lines (empty, only spaces, or only commas, as a spreadsheet exports an emptied row) are skipped, but
     * each row's {@code rowNumber} still counts them, as a spreadsheet does, so a message about row 7 points at
     * row 7 when the file is opened.
     */
    public ManifestValidationResult parse(final String rawCsv, final String anonScript, final TransferMode mode)
            throws ManifestValidationException {
        final String bomless = stripBom(rawCsv);
        if (StringUtils.isBlank(bomless)) {
            throw new ManifestValidationException(HttpStatus.BAD_REQUEST, "The manifest is empty.");
        }

        // Blank lines above the header would otherwise be read as the header; drop them but keep their count.
        int start = 0;
        int linesAboveHeader = 0;
        while (start < bomless.length()) {
            int end = start;
            while (end < bomless.length() && bomless.charAt(end) != '\r' && bomless.charAt(end) != '\n') {
                end++;
            }
            if (!isBlankLine(bomless.substring(start, end))) {
                break;
            }
            start = bomless.startsWith("\r\n", end) ? end + 2 : end + 1;
            linesAboveHeader++;
        }
        if (start >= bomless.length()) {
            throw new ManifestValidationException(HttpStatus.BAD_REQUEST, "The manifest is empty.");
        }
        final String csv = bomless.substring(start);

        // Empty lines are kept as records so the parser's record numbers line up with the file's rows.
        final CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(false)
                .setTrim(true)
                .build();

        final int maxRows = policy.getManifestMaxRows();
        final List<CSVRecord> records = new ArrayList<>();
        final List<String> headers;
        try (CSVParser parser = CSVParser.parse(csv, format)) {
            headers = parser.getHeaderNames();
            for (final CSVRecord record : parser) {
                if (isBlankRecord(record)) {
                    continue;
                }
                if (records.size() >= maxRows) {
                    throw new ManifestValidationException(HttpStatus.PAYLOAD_TOO_LARGE,
                            "The manifest exceeds the maximum of " + maxRows + " rows.");
                }
                records.add(record);
            }
        } catch (ManifestValidationException e) {
            throw e;
        } catch (Exception e) {
            // Any commons-csv failure (bad header, unclosed quote, I/O) is an unparseable manifest.
            throw new ManifestValidationException(HttpStatus.BAD_REQUEST,
                    "The manifest could not be parsed: " + e.getMessage());
        }

        final ManifestValidationResult result = new ManifestValidationResult();
        result.setColumns(new ArrayList<>(headers));

        final List<String> missing = new ArrayList<>();
        if (!headers.contains(COL_SUBJECT)) {
            missing.add(COL_SUBJECT);
        }
        if (!headers.contains(COL_SESSION)) {
            missing.add(COL_SESSION);
        }
        result.setMissingColumns(missing);
        result.setRequiredPresent(missing.isEmpty());

        final List<String> reserved = new ArrayList<>();
        final List<String> routing  = new ArrayList<>();
        final List<String> value    = new ArrayList<>();
        for (final String header : headers) {
            if (COL_SUBJECT.equals(header) || COL_SESSION.equals(header)) {
                continue;
            }
            if (COL_DEST_SUBJECT.equals(header) || COL_DEST_SESSION.equals(header)) {
                routing.add(header);            // recognized routing column (dual-purpose; see below)
            } else if (header.startsWith("_")) {
                reserved.add(header);
            } else {
                value.add(header);
            }
        }
        result.setReservedColumns(reserved);
        result.setRoutingColumns(routing);
        result.setValueColumns(value);
        result.setTotalRows(records.size());

        // Routing columns are dual-purpose: they drive placement AND are ${csv.*}-substitutable, so they
        // count as available (bound) columns alongside the value columns.
        final Set<String> boundColumns = new LinkedHashSet<>(value);
        boundColumns.addAll(routing);
        if (StringUtils.isNotBlank(anonScript)) {
            final List<String> unbound = scriptCompiler.unboundPlaceholders(anonScript, boundColumns);
            result.setScriptBinding(new ScriptBinding(unbound.isEmpty(), unbound));
        }

        // Without the required columns there is nothing to resolve; return the classification for the UI.
        if (!result.isRequiredPresent()) {
            result.setSummary(new ManifestSummary(0, 0));
            return result;
        }

        // A placeholder is "bound" when its column is available; an empty cell for a bound column is a soft
        // warning (submittable), not a hard error.
        final Set<String> boundPlaceholders = StringUtils.isNotBlank(anonScript)
                ? new LinkedHashSet<>(scriptCompiler.extractPlaceholders(anonScript))
                : new LinkedHashSet<>();
        boundPlaceholders.retainAll(boundColumns);

        final boolean hasDestSubject = headers.contains(COL_DEST_SUBJECT);
        final boolean hasDestSession = headers.contains(COL_DEST_SESSION);

        final List<ManifestRow> rows = new ArrayList<>(records.size());
        int index = 0;
        for (final CSVRecord record : records) {
            index++;
            final ManifestRow row = new ManifestRow();
            row.setIndex(index);
            // Record numbers start at 1 for the first line after the header and count blank lines.
            row.setRowNumber(linesAboveHeader + 1 + (int) record.getRecordNumber());
            row.setSourceSubjectLabel(cell(record, COL_SUBJECT));
            row.setSourceSessionLabel(cell(record, COL_SESSION));
            if (hasDestSubject) {
                row.setDestinationSubjectLabel(StringUtils.trimToNull(cell(record, COL_DEST_SUBJECT)));
            }
            if (hasDestSession) {
                row.setDestinationSessionLabel(StringUtils.trimToNull(cell(record, COL_DEST_SESSION)));
            }

            // csv_values carries value columns + routing columns (routing is ${csv.*}-substitutable too).
            final Map<String, String> csvValues = new LinkedHashMap<>();
            for (final String column : value) {
                csvValues.put(column, cell(record, column));
            }
            for (final String column : routing) {
                csvValues.put(column, cell(record, column));
            }
            row.setCsvValues(csvValues);

            // Value columns: DICOM-safe charset. Routing columns: XNAT-label charset (blank = derive, no error).
            for (final String column : value) {
                if (!scriptCompiler.isValueSafe(csvValues.get(column))) {
                    row.getValueErrors().add("Value for '" + column
                            + "' contains characters outside the allowed set.");
                }
            }
            for (final String column : routing) {
                final String label = csvValues.get(column);
                if (StringUtils.isNotBlank(label) && !LABEL_PATTERN.matcher(label).matches()) {
                    row.getValueErrors().add("Value for '" + column + "' is not a valid XNAT label (letters, "
                            + "digits, _ and - ; must start alphanumeric; other characters are rewritten to "
                            + "'_' at ingest, so the label would not route as written).");
                }
            }
            for (final String column : boundPlaceholders) {
                if (StringUtils.isBlank(csvValues.get(column))) {
                    row.getValueWarnings().add("Value for '" + column
                            + "' is empty but the script binds ${csv." + column + "}.");
                }
            }
            row.setSubjectRow(StringUtils.isBlank(row.getSourceSessionLabel()));
            if (row.isSubjectRow() && row.getDestinationSessionLabel() != null) {
                row.getValueErrors().add("'" + COL_DEST_SESSION + "' can't be set on a subject row: it would apply "
                        + "to every session of the subject. List the subject's sessions individually instead.");
            }
            rows.add(row);
        }
        flagDuplicateSessions(rows, mode == null || mode == TransferMode.REIMPORT);
        flagDuplicateDestinationLabels(rows);
        result.setRows(rows);
        result.setSummary(new ManifestSummary(0, 0));   // filled after resolution
        return result;
    }

    /**
     * Marks {@code duplicate_session} on every row that covers what another row also covers: the same subject and
     * session listed twice, the same subject row twice, and, for Reimport (where a subject row means all of the
     * subject's sessions), a subject row together with any other row for that subject. For Share/Clone a subject
     * row is the subject record alone, so it can sit beside rows for that subject's sessions. Labels only, so it
     * needs no XNAT lookup. Rows with a blank subject label are left to the resolver.
     */
    private static void flagDuplicateSessions(final List<ManifestRow> rows, final boolean reimport) {
        final Map<String, List<ManifestRow>> bySubject = new LinkedHashMap<>();
        for (final ManifestRow row : rows) {
            if (StringUtils.isNotBlank(row.getSourceSubjectLabel())) {
                bySubject.computeIfAbsent(row.getSourceSubjectLabel(), k -> new ArrayList<>()).add(row);
            }
        }
        for (final List<ManifestRow> subjectRows : bySubject.values()) {
            final boolean coversAllSessions = reimport && subjectRows.stream().anyMatch(ManifestRow::isSubjectRow);
            if (coversAllSessions && subjectRows.size() > 1) {
                subjectRows.forEach(row -> row.setStatus(ManifestRow.STATUS_DUPLICATE_SESSION));
                continue;
            }
            final Map<String, List<ManifestRow>> bySession = new LinkedHashMap<>();
            for (final ManifestRow row : subjectRows) {
                bySession.computeIfAbsent(row.getSourceSessionLabel(), k -> new ArrayList<>()).add(row);
            }
            for (final List<ManifestRow> sessionRows : bySession.values()) {
                if (sessionRows.size() > 1) {
                    sessionRows.forEach(row -> row.setStatus(ManifestRow.STATUS_DUPLICATE_SESSION));
                }
            }
        }
    }

    /**
     * Adds a {@code duplicate_destination_label} value error to each row whose {@value #COL_DEST_SESSION} is also
     * another row's: two sessions can't land under one session label in the destination project. Labels only;
     * a label that already exists in the destination still fails per session at import.
     */
    private static void flagDuplicateDestinationLabels(final List<ManifestRow> rows) {
        final Map<String, List<ManifestRow>> byLabel = new LinkedHashMap<>();
        for (final ManifestRow row : rows) {
            if (!row.isSubjectRow() && row.getDestinationSessionLabel() != null) {
                byLabel.computeIfAbsent(row.getDestinationSessionLabel(), k -> new ArrayList<>()).add(row);
            }
        }
        for (final Map.Entry<String, List<ManifestRow>> entry : byLabel.entrySet()) {
            final List<ManifestRow> sameLabel = entry.getValue();
            if (sameLabel.size() < 2) {
                continue;
            }
            for (final ManifestRow row : sameLabel) {
                final List<String> others = new ArrayList<>();
                for (final ManifestRow other : sameLabel) {
                    if (other != row) {
                        others.add(String.valueOf(other.getRowNumber()));
                    }
                }
                row.getValueErrors().add("duplicate_destination_label: '" + entry.getKey() + "' is also the "
                        + COL_DEST_SESSION + " of row " + String.join(", ", others) + ".");
            }
        }
    }

    /** A line of only spaces and commas: an empty spreadsheet row, however many columns it was exported with. */
    private static boolean isBlankLine(final String line) {
        return StringUtils.isBlank(StringUtils.remove(line, ','));
    }

    /** A record whose cells all trim to nothing: an empty line, or one of only spaces and commas. */
    private static boolean isBlankRecord(final CSVRecord record) {
        return record.stream().allMatch(String::isEmpty);
    }

    /** A mapped, present cell value; {@code ""} when the row is short a column (commons-csv would throw). */
    private static String cell(final CSVRecord record, final String column) {
        return record.isSet(column) ? record.get(column) : "";
    }

    private static String stripBom(final String s) {
        return (s != null && !s.isEmpty() && s.charAt(0) == '\uFEFF') ? s.substring(1) : s;
    }
}
