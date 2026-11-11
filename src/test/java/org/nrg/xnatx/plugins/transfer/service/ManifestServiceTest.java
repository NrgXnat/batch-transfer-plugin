package org.nrg.xnatx.plugins.transfer.service;

import org.junit.Before;
import org.junit.Test;
import org.nrg.dicom.mizer.service.MizerService;
import org.nrg.xnatx.plugins.transfer.model.ManifestRow;
import org.nrg.xnatx.plugins.transfer.model.ManifestValidationResult;
import org.springframework.http.HttpStatus;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Parsing + classification + per-cell value checks are pure text, so this exercises the real
 * {@link ManifestService} against a real {@link ScriptCompiler} (its Mizer bean is never touched by these
 * text-only calls) with the default value charset. Session resolution is the {@link PreflightResolver}'s job
 * and is tested separately.
 */
public class ManifestServiceTest {

    private ManifestService service;

    @Before
    public void setUp() {
        final BatchTransferPolicy policy = mock(BatchTransferPolicy.class);
        when(policy.getAnonValueCharset()).thenReturn("A-Za-z0-9 ^_.-");
        when(policy.getManifestMaxRows()).thenReturn(5000);

        final ScriptCompiler compiler = new ScriptCompiler(mock(MizerService.class), policy);
        service = new ManifestService(policy, compiler);
    }

    private ManifestValidationResult parse(final String csv) throws ManifestValidationException {
        return service.parse(csv, null);
    }

    @Test
    public void classifiesRequiredReservedAndValueColumns() throws Exception {
        final String csv = "source_subject_label,source_session_label,destination_patient_id,_notes\n"
                + "S1,SESS1,ANON-1,hello\n";
        final ManifestValidationResult r = parse(csv);

        assertTrue(r.isRequiredPresent());
        assertTrue(r.getMissingColumns().isEmpty());
        assertEquals(Arrays.asList("_notes"), r.getReservedColumns());
        assertEquals(Arrays.asList("destination_patient_id"), r.getValueColumns());
        assertEquals(1, r.getTotalRows());

        final ManifestRow row = r.getRows().get(0);
        assertEquals(1, row.getIndex());
        assertEquals("the header is row 1", 2, row.getRowNumber());
        assertEquals("S1", row.getSourceSubjectLabel());
        assertEquals("SESS1", row.getSourceSessionLabel());
        // value columns only — required + reserved columns are not exposed as ${csv.*}
        assertEquals(1, row.getCsvValues().size());
        assertEquals("ANON-1", row.getCsvValues().get("destination_patient_id"));
    }

    @Test
    public void missingRequiredColumnIsReportedNotThrown() throws Exception {
        final String csv = "source_subject_label,destination_patient_id\nS1,ANON-1\n";
        final ManifestValidationResult r = parse(csv);

        assertFalse(r.isRequiredPresent());
        assertTrue(r.getMissingColumns().contains("source_session_label"));
        assertTrue("no resolution attempted without the required columns", r.getRows().isEmpty());
    }

    @Test
    public void handlesCrlfBlankLinesAndQuotedCommas() throws Exception {
        final String csv = "source_subject_label,source_session_label,destination_patient_id\r\n"
                + "S1,SESS1,\"LAST, FIRST\"\r\n"
                + "\r\n"                                   // blank line — ignored
                + "S2,SESS2,ANON-2\r\n";
        final ManifestValidationResult r = parse(csv);

        assertEquals(2, r.getTotalRows());
        assertEquals("LAST, FIRST", r.getRows().get(0).getCsvValues().get("destination_patient_id"));
        assertEquals("S2", r.getRows().get(1).getSourceSubjectLabel());
        assertEquals(2, r.getRows().get(0).getRowNumber());
        assertEquals("the blank line still takes a row", 4, r.getRows().get(1).getRowNumber());
    }

    @Test
    public void rowNumbersMatchASpreadsheet() throws Exception {
        final ManifestValidationResult r = parse("\n\r\n"                          // rows 1-2: blank, above the header
                + "source_subject_label,source_session_label,_notes\n"             // row 3: the header
                + "S1,SESS1,\"two\nlines\"\n"                                     // row 4: a quoted line break stays in the row
                + "   \n"                                                          // row 5: only spaces, skipped
                + "S2,SESS2,x\n");                                                 // row 6

        assertTrue("blank lines above the header are not read as the header", r.isRequiredPresent());
        assertEquals(2, r.getTotalRows());
        assertEquals(1, r.getRows().get(0).getIndex());
        assertEquals(4, r.getRows().get(0).getRowNumber());
        assertEquals(2, r.getRows().get(1).getIndex());
        assertEquals(6, r.getRows().get(1).getRowNumber());
    }

    @Test
    public void linesOfSpacesOrCommasAboveTheHeaderAreSkipped() throws Exception {
        final ManifestValidationResult r = parse("  \n"                            // row 1: only spaces
                + ",,\r\n"                                                       // row 2: an emptied spreadsheet row
                + "source_subject_label,source_session_label\n"                  // row 3: the header
                + "S1,SESS1\n");                                                 // row 4

        assertTrue("a blank line is not read as the header", r.isRequiredPresent());
        assertEquals(1, r.getTotalRows());
        assertEquals(4, r.getRows().get(0).getRowNumber());
    }

    @Test
    public void aRowOfOnlyCommasIsSkippedButARowWithAValueIsNot() throws Exception {
        final ManifestValidationResult r = parse("source_subject_label,source_session_label,pid\n"
                + "S1,SESS1,A\n"                                                 // row 2
                + ", ,\n"                                                        // row 3: an emptied spreadsheet row
                + ",,B\n"                                                        // row 4: it has a value, so it's kept
                + "S2,SESS2,C\n");                                               // row 5

        assertEquals(3, r.getTotalRows());
        assertEquals(4, r.getRows().get(1).getRowNumber());
        assertEquals("B", r.getRows().get(1).getCsvValues().get("pid"));
        assertEquals(5, r.getRows().get(2).getRowNumber());
    }

    @Test
    public void blankLinesDoNotCountTowardTheRowCap() throws Exception {
        final BatchTransferPolicy tinyCap = mock(BatchTransferPolicy.class);
        when(tinyCap.getAnonValueCharset()).thenReturn("A-Za-z0-9 ^_.-");
        when(tinyCap.getManifestMaxRows()).thenReturn(2);
        final ManifestService capped = new ManifestService(tinyCap,
                new ScriptCompiler(mock(MizerService.class), tinyCap));

        final ManifestValidationResult r = capped.parse(
                "source_subject_label,source_session_label\nA,1\n\n\nB,2\n\n", null);

        assertEquals(2, r.getTotalRows());
    }

    @Test
    public void stripsLeadingByteOrderMark() throws Exception {
        final String csv = "\uFEFFsource_subject_label,source_session_label\nS1,SESS1\n";
        final ManifestValidationResult r = parse(csv);

        assertTrue("BOM must not corrupt the first header name", r.isRequiredPresent());
        assertEquals("S1", r.getRows().get(0).getSourceSubjectLabel());
    }

    @Test
    public void flagsValuesOutsideTheSafeCharset() throws Exception {
        final String csv = "source_subject_label,source_session_label,destination_patient_id\n"
                + "S1,SESS1,\"bad\"\"value\"\n";       // contains a double-quote → unsafe
        final ManifestValidationResult r = parse(csv);

        assertFalse(r.getRows().get(0).getValueErrors().isEmpty());
    }

    @Test
    public void warnsOnEmptyCellForABoundPlaceholder() throws Exception {
        final String script = "version \"6.1\"\n(0010,0020) := \"${csv.destination_patient_id}\"";
        final String csv = "source_subject_label,source_session_label,destination_patient_id\n"
                + "S1,SESS1,\n";                        // empty bound cell
        final ManifestValidationResult r = service.parse(csv, script);

        assertTrue(r.getScriptBinding().isBound());
        assertFalse(r.getRows().get(0).getValueWarnings().isEmpty());
    }

    @Test
    public void reportsUnboundPlaceholderInScriptBinding() throws Exception {
        final String script = "version \"6.1\"\n(0010,0020) := \"${csv.not_a_column}\"";
        final String csv = "source_subject_label,source_session_label,destination_patient_id\n"
                + "S1,SESS1,ANON-1\n";
        final ManifestValidationResult r = service.parse(csv, script);

        assertFalse(r.getScriptBinding().isBound());
        assertTrue(r.getScriptBinding().getUnbound().contains("not_a_column"));
    }

    @Test
    public void classifiesRoutingColumnsAndPopulatesRows() throws Exception {
        final String csv = "source_subject_label,source_session_label,destination_subject_label,destination_session_label,destination_patient_id\n"
                + "S1,SESS1,ANON-042,STUDY2_042,ANON-042\n";
        final ManifestValidationResult r = parse(csv);

        assertEquals(Arrays.asList("destination_subject_label", "destination_session_label"), r.getRoutingColumns());
        // routing columns are recognized separately — not lumped into value columns
        assertEquals(Arrays.asList("destination_patient_id"), r.getValueColumns());

        final ManifestRow row = r.getRows().get(0);
        assertEquals("ANON-042", row.getDestinationSubjectLabel());
        assertEquals("STUDY2_042", row.getDestinationSessionLabel());
        // dual-purpose: routing values are also exposed to ${csv.*} substitution
        assertEquals("ANON-042", row.getCsvValues().get("destination_subject_label"));
    }

    @Test
    public void routingColumnIsAvailableForScriptBinding() throws Exception {
        final String script = "version \"6.1\"\n(0010,0020) := \"${csv.destination_subject_label}\"";
        final String csv = "source_subject_label,source_session_label,destination_subject_label\n"
                + "S1,SESS1,ANON-042\n";
        final ManifestValidationResult r = service.parse(csv, script);

        assertTrue("a routing column binds ${csv.*}", r.getScriptBinding().isBound());
    }

    @Test
    public void flagsAnInvalidRoutingLabel() throws Exception {
        // A space is DICOM-safe (passes the ${csv.*} charset) but NOT a valid XNAT label — exercises the
        // stricter routing charset, not isValueSafe.
        final String csv = "source_subject_label,source_session_label,destination_subject_label\n"
                + "S1,SESS1,ANON 042\n";
        final ManifestValidationResult r = parse(csv);

        assertFalse(r.getRows().get(0).getValueErrors().isEmpty());
    }

    @Test
    public void flagsADottedRoutingLabel() throws Exception {
        // A dot is DICOM-safe but XNAT's cleanValue rewrites it to '_', so the label wouldn't round-trip —
        // routing labels must be restricted to what XNAT keeps verbatim.
        final String csv = "source_subject_label,source_session_label,destination_subject_label\n"
                + "S1,SESS1,STUDY2.042\n";
        final ManifestValidationResult r = parse(csv);

        assertFalse(r.getRows().get(0).getValueErrors().isEmpty());
    }

    @Test
    public void blankRoutingLabelIsNotAnError() throws Exception {
        final String csv = "source_subject_label,source_session_label,destination_subject_label\n"
                + "S1,SESS1,\n";                  // blank → derive normally, not an error
        final ManifestValidationResult r = parse(csv);

        assertTrue(r.getRows().get(0).getValueErrors().isEmpty());
        assertNull(r.getRows().get(0).getDestinationSubjectLabel());
    }

    @Test
    public void rejectsAManifestOverTheRowCap() {
        final BatchTransferPolicy tinyCap = mock(BatchTransferPolicy.class);
        when(tinyCap.getAnonValueCharset()).thenReturn("A-Za-z0-9 ^_.-");
        when(tinyCap.getManifestMaxRows()).thenReturn(2);
        final ManifestService capped = new ManifestService(tinyCap,
                new ScriptCompiler(mock(MizerService.class), tinyCap));

        final String csv = "source_subject_label,source_session_label\nA,1\nB,2\nC,3\n";
        try {
            capped.parse(csv, null);
            fail("expected ManifestValidationException for exceeding the row cap");
        } catch (ManifestValidationException e) {
            assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, e.getStatus());
        }
    }

    @Test
    public void rejectsAnEmptyManifest() {
        try {
            parse("   ");
            fail("expected ManifestValidationException for an empty manifest");
        } catch (ManifestValidationException e) {
            assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        }
    }

    @Test
    public void rejectsAManifestOfOnlyBlankRows() {
        try {
            parse(",,\n , \n");
            fail("expected ManifestValidationException for a manifest with no header");
        } catch (ManifestValidationException e) {
            assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
            assertEquals("The manifest is empty.", e.getMessage());
        }
    }

    // ---- subject rows and overlap ------------------------------------------------------------------------

    private static final String HEADER = "source_subject_label,source_session_label,destination_session_label\n";

    @Test
    public void aBlankSessionCellMakesASubjectRow() throws Exception {
        final ManifestValidationResult r = parse("source_subject_label,source_session_label,pid\n"
                + "S1,,ANON-1\n"
                + "S2,SESS2,ANON-2\n");

        assertTrue(r.isRequiredPresent());
        final ManifestRow subject = r.getRows().get(0);
        assertTrue(subject.isSubjectRow());
        assertEquals("", subject.getSourceSessionLabel());
        assertNull("resolution is the resolver's job", subject.getStatus());
        assertEquals("ANON-1", subject.getCsvValues().get("pid"));
        assertFalse(r.getRows().get(1).isSubjectRow());
    }

    @Test
    public void aSubjectRowAndASessionRowForTheSameSubjectAreDuplicateSession() throws Exception {
        final ManifestValidationResult r = parse("source_subject_label,source_session_label\n"
                + "S1,\n"
                + "S1,SESS1\n"
                + "S2,SESS2\n");

        assertEquals(ManifestRow.STATUS_DUPLICATE_SESSION, r.getRows().get(0).getStatus());
        assertEquals(ManifestRow.STATUS_DUPLICATE_SESSION, r.getRows().get(1).getStatus());
        assertNull("another subject is unaffected", r.getRows().get(2).getStatus());
    }

    @Test
    public void twoSubjectRowsForTheSameSubjectAreDuplicateSession() throws Exception {
        final ManifestValidationResult r = parse("source_subject_label,source_session_label\nS1,\nS1,\n");

        assertEquals(ManifestRow.STATUS_DUPLICATE_SESSION, r.getRows().get(0).getStatus());
        assertEquals(ManifestRow.STATUS_DUPLICATE_SESSION, r.getRows().get(1).getStatus());
    }

    @Test
    public void theSameSessionListedTwiceIsDuplicateSession() throws Exception {
        final ManifestValidationResult r = parse("source_subject_label,source_session_label\n"
                + "S1,SESS1\n"
                + "S1,SESS2\n"
                + "S1,SESS1\n");

        assertEquals(ManifestRow.STATUS_DUPLICATE_SESSION, r.getRows().get(0).getStatus());
        assertNull("a different session of the same subject is fine", r.getRows().get(1).getStatus());
        assertEquals(ManifestRow.STATUS_DUPLICATE_SESSION, r.getRows().get(2).getStatus());
    }

    @Test
    public void aDestinationSessionLabelOnASubjectRowIsAValueError() throws Exception {
        final ManifestValidationResult r = parse(HEADER + "S1,,STUDY2_S1\n");

        final ManifestRow row = r.getRows().get(0);
        assertTrue(row.isSubjectRow());
        assertEquals(1, row.getValueErrors().size());
        assertTrue(row.getValueErrors().get(0).contains("subject row"));
    }

    @Test
    public void aRepeatedDestinationSessionLabelIsFlaggedOnEachRow() throws Exception {
        final ManifestValidationResult r = parse(HEADER
                + "S1,SESS1,STUDY2_A\n"
                + "S2,SESS2,STUDY2_B\n"
                + "S3,SESS3,STUDY2_A\n");

        final ManifestRow first = r.getRows().get(0);
        final ManifestRow third = r.getRows().get(2);
        assertEquals(1, first.getValueErrors().size());
        assertTrue(first.getValueErrors().get(0).startsWith("duplicate_destination_label"));
        assertTrue("names the other row as a spreadsheet numbers it", first.getValueErrors().get(0).contains("row 4"));
        assertTrue(third.getValueErrors().get(0).contains("row 2"));
        assertTrue(r.getRows().get(1).getValueErrors().isEmpty());
        assertNull("a destination-label clash is a value error, not a status", first.getStatus());
    }
}
