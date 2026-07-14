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
}
