package org.nrg.xnatx.plugins.transfer.service;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.lang3.StringUtils;
import org.nrg.xnatx.plugins.transfer.model.ManifestRow;
import org.nrg.xnatx.plugins.transfer.model.ManifestSummary;
import org.nrg.xnatx.plugins.transfer.model.ManifestValidationResult;
import org.nrg.xnatx.plugins.transfer.model.ScriptBinding;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses a session-level manifest CSV and classifies its columns, with per-cell value checks and (optionally)
 * {@code ${csv.*}} binding. Pure text work — no XNAT involvement, so it is unit-testable without XDAT/Spring;
 * per-row session resolution is the {@link PreflightResolver}'s job, run by the API after this.
 *
 * <p>Column roles:
 * <ul>
 *   <li><b>Required</b> columns {@value #COL_SUBJECT} + {@value #COL_SESSION} identify the session.</li>
 *   <li><b>Reserved</b> columns are {@code _}-prefixed (notes; never exposed to a script).</li>
 *   <li><b>Value</b> columns are the rest — the {@code ${csv.*}}-eligible set.</li>
 * </ul>
 * A malformed CSV is a 400 and an over-cap manifest a 413 (both {@link ManifestValidationException}); a
 * <i>parsed</i> manifest that merely lacks required columns comes back as a 200 result the UI renders.
 */
@Service
public class ManifestService {

    public static final String COL_SUBJECT = "source_subject_label";
    public static final String COL_SESSION = "source_session_label";

    private final BatchTransferPolicy policy;
    private final ScriptCompiler scriptCompiler;

    @Autowired
    public ManifestService(final BatchTransferPolicy policy, final ScriptCompiler scriptCompiler) {
        this.policy = policy;
        this.scriptCompiler = scriptCompiler;
    }

    /**
     * Parse and classify {@code rawCsv}, filling every field of the result except each row's resolution
     * ({@code status}/{@code resolvedId}/{@code availableSessions}) and the summary tally, which the caller
     * completes after running the {@link PreflightResolver}.
     */
    public ManifestValidationResult parse(final String rawCsv, final String anonScript)
            throws ManifestValidationException {
        final String csv = stripBom(rawCsv);
        if (StringUtils.isBlank(csv)) {
            throw new ManifestValidationException(HttpStatus.BAD_REQUEST, "The manifest is empty.");
        }

        final CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .build();

        final int maxRows = policy.getManifestMaxRows();
        final List<CSVRecord> records = new ArrayList<>();
        final List<String> headers;
        try (CSVParser parser = CSVParser.parse(csv, format)) {
            headers = parser.getHeaderNames();
            for (final CSVRecord record : parser) {
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
        final List<String> value = new ArrayList<>();
        for (final String header : headers) {
            if (COL_SUBJECT.equals(header) || COL_SESSION.equals(header)) {
                continue;
            }
            if (header.startsWith("_")) {
                reserved.add(header);
            } else {
                value.add(header);
            }
        }
        result.setReservedColumns(reserved);
        result.setValueColumns(value);
        result.setTotalRows(records.size());

        final Set<String> valueColumnSet = new LinkedHashSet<>(value);
        if (StringUtils.isNotBlank(anonScript)) {
            final List<String> unbound = scriptCompiler.unboundPlaceholders(anonScript, valueColumnSet);
            result.setScriptBinding(new ScriptBinding(unbound.isEmpty(), unbound));
        }

        // Without the required columns there is nothing to resolve; return the classification for the UI.
        if (!result.isRequiredPresent()) {
            result.setSummary(new ManifestSummary(0, 0));
            return result;
        }

        // A placeholder is "bound" when its column is an available value column; an empty cell for such a
        // column is a soft warning (submittable), not a hard error.
        final Set<String> boundPlaceholders = StringUtils.isNotBlank(anonScript)
                ? new LinkedHashSet<>(scriptCompiler.extractPlaceholders(anonScript))
                : new LinkedHashSet<>();
        boundPlaceholders.retainAll(valueColumnSet);

        final List<ManifestRow> rows = new ArrayList<>(records.size());
        int index = 0;
        for (final CSVRecord record : records) {
            index++;
            final ManifestRow row = new ManifestRow();
            row.setIndex(index);
            row.setSourceSubjectLabel(cell(record, COL_SUBJECT));
            row.setSourceSessionLabel(cell(record, COL_SESSION));

            final Map<String, String> csvValues = new LinkedHashMap<>();
            for (final String column : value) {
                csvValues.put(column, cell(record, column));
            }
            row.setCsvValues(csvValues);

            for (final Map.Entry<String, String> entry : csvValues.entrySet()) {
                if (!scriptCompiler.isValueSafe(entry.getValue())) {
                    row.getValueErrors().add("Value for '" + entry.getKey()
                            + "' contains characters outside the allowed set.");
                }
            }
            for (final String column : boundPlaceholders) {
                if (StringUtils.isBlank(csvValues.get(column))) {
                    row.getValueWarnings().add("Value for '" + column
                            + "' is empty but the script binds ${csv." + column + "}.");
                }
            }
            rows.add(row);
        }
        result.setRows(rows);
        result.setSummary(new ManifestSummary(0, 0));   // matched/not_found filled after resolution
        return result;
    }

    /** A mapped, present cell value; {@code ""} when the row is short a column (commons-csv would throw). */
    private static String cell(final CSVRecord record, final String column) {
        return record.isSet(column) ? record.get(column) : "";
    }

    private static String stripBom(final String s) {
        return (s != null && !s.isEmpty() && s.charAt(0) == '\uFEFF') ? s.substring(1) : s;
    }
}
