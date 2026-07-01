package org.nrg.xnatx.plugins.transfer.service;

import org.junit.Before;
import org.junit.Test;
import org.nrg.dicom.mizer.exceptions.MizerException;
import org.nrg.dicom.mizer.service.MizerService;
import org.nrg.xnatx.plugins.transfer.jms.preferences.BatchTransferQueuePrefsBean;
import org.nrg.xnatx.plugins.transfer.model.TransferMode;
import org.nrg.xnatx.plugins.transfer.model.TransferRequest;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ScriptCompiler}. The pure-text methods (placeholders, binding, sanitize,
 * substitution, restriction) and the orchestrating {@code validateBatch} run against a mocked
 * {@link BatchTransferQueuePrefsBean} (default limits) and {@link MizerService} (the parse seam), so no real
 * DicomEdit engine is needed.
 */
public class ScriptCompilerTest {

    private MizerService mizer;
    private ScriptCompiler compiler;

    /**
     * Plain default-valued policy — referencing the bean's compile-time constants (inlined, so the XDAT
     * preference hierarchy is never initialized) rather than mocking the bean, whose supertype init trips a
     * test-classpath slf4j/log4j conflict.
     */
    private static final AnonScriptPolicy DEFAULT_POLICY = new AnonScriptPolicy() {
        @Override public Integer getMaxAnonScriptBytes() {
            return Integer.valueOf(BatchTransferQueuePrefsBean.MAX_ANON_SCRIPT_BYTES_DFLT);
        }
        @Override public String getAnonValueCharset() {
            return BatchTransferQueuePrefsBean.ANON_VALUE_CHARSET_DFLT;
        }
        @Override public String getRestrictedAnonVerbs() {
            return BatchTransferQueuePrefsBean.RESTRICTED_ANON_VERBS_DFLT;
        }
        @Override public String getAllowedAnonVersionPattern() {
            return BatchTransferQueuePrefsBean.ALLOWED_ANON_VERSION_PATTERN_DFLT;
        }
    };

    @Before
    public void setUp() {
        mizer = mock(MizerService.class);
        compiler = new ScriptCompiler(mizer, DEFAULT_POLICY);
    }

    // ---- placeholders / binding -------------------------------------------------------------------

    @Test
    public void extractsDistinctCsvPlaceholders() {
        final Set<String> ph = compiler.extractPlaceholders(
                "version \"6.1\"\n(0010,0010) := \"${csv.name}\"\n(0010,0020) := \"${csv.id}\"\n# ${csv.name} again");
        assertEquals(2, ph.size());
        assertTrue(ph.contains("name"));
        assertTrue(ph.contains("id"));
    }

    @Test
    public void unboundReportsMissingColumns() {
        final List<String> unbound = compiler.unboundPlaceholders(
                "(0010,0020) := \"${csv.id}\"", Collections.<String>singleton("other"));
        assertEquals(singletonList("id"), unbound);
        assertTrue(compiler.unboundPlaceholders("(0010,0020) := \"${csv.id}\"",
                Collections.<String>singleton("id")).isEmpty());
    }

    // ---- sanitize ---------------------------------------------------------------------------------

    @Test
    public void sanitizeAcceptsSafeRejectsBreakers() {
        assertTrue(compiler.isValueSafe("ANON-042 ^_."));
        assertTrue(compiler.isValueSafe(""));
        assertTrue(compiler.isValueSafe(null));
        assertFalse(compiler.isValueSafe("a\"b"));   // quote
        assertFalse(compiler.isValueSafe("a$b"));    // dollar
        assertFalse(compiler.isValueSafe("a;b"));    // semicolon
        assertFalse(compiler.isValueSafe("a\nb"));   // newline
    }

    // ---- substitution -----------------------------------------------------------------------------

    @Test
    public void compileSubstitutesCsvAndLeavesNativeVars() {
        final Map<String, String> row = new HashMap<>();
        row.put("name", "ANON^PT");
        final String out = compiler.compile(
                "(0010,0010) := \"${csv.name}\"\n(0008,0018) := \"${UID}\"", row);
        assertTrue(out.contains("\"ANON^PT\""));
        assertTrue("native ${UID} must pass through untouched", out.contains("${UID}"));
    }

    // ---- restriction ------------------------------------------------------------------------------

    @Test
    public void restrictionRejectsMissingAndDisallowedVersion() {
        assertFalse(compiler.restrictionViolations("(0010,0010) := \"X\"").isEmpty());
        assertFalse(compiler.restrictionViolations("version \"4.0\"\n(0010,0010) := \"X\"").isEmpty());
        assertTrue(compiler.restrictionViolations("version \"6.1\"\n(0010,0010) := \"X\"").isEmpty());
    }

    @Test
    public void restrictionRejectsEachDeniedVerb() {
        for (final String verb : new String[]{"mapUID", "mapReferencedUIDs", "lookup", "getURL", "alterPixels", "newUID"}) {
            final String script = "version \"6.1\"\n(0010,0020) := " + verb + "(this)";
            assertFalse("expected violation for " + verb, compiler.restrictionViolations(script).isEmpty());
        }
    }

    @Test
    public void restrictionAllowsCleanHashUidScript() {
        assertTrue(compiler.restrictionViolations(
                "version \"6.1\"\n(0010,0020) := hashUID(this)").isEmpty());
    }

    @Test
    public void restrictionDoesNotTripOnVerbInCommentOrValue() {
        assertTrue("verb in a // comment must not trip", compiler.restrictionViolations(
                "version \"6.1\"\n// do not use mapUID(x) here\n(0010,0010) := \"Y\"").isEmpty());
        assertTrue("verb inside a quoted value must not trip", compiler.restrictionViolations(
                "version \"6.1\"\n(0010,0010) := \"mapUID(x)\"").isEmpty());
    }

    // ---- validateBatch ----------------------------------------------------------------------------

    @Test
    public void validateBatchPassesForBoundSafeValidScript() throws Exception {
        final TransferRequest req = reimport("E1", csv("pid", "ANON-1"));
        compiler.validateBatch("version \"6.1\"\n(0010,0020) := \"${csv.pid}\"", singletonList(req));
        // no exception = pass (parse seam returns Mockito's default empty set)
    }

    @Test
    public void validateBatchRejectsUnboundPlaceholder() {
        final TransferRequest req = reimport("E1", Collections.<String, String>emptyMap());
        expectInvalid("version \"6.1\"\n(0010,0020) := \"${csv.pid}\"", req);
    }

    @Test
    public void validateBatchRejectsUnsafeValue() {
        final TransferRequest req = reimport("E1", csv("pid", "bad\"value"));
        expectInvalid("version \"6.1\"\n(0010,0020) := \"${csv.pid}\"", req);
    }

    @Test
    public void validateBatchRejectsRestrictedVerb() {
        final TransferRequest req = reimport("E1", Collections.<String, String>emptyMap());
        expectInvalid("version \"6.1\"\n(0010,0020) := mapUID(this)", req);
    }

    @Test
    public void validateBatchRejectsMalformedScriptFromParseSeam() throws Exception {
        when(mizer.createContext(any(), any(), any(), anyLong(), any(), anyBoolean(), anyBoolean()))
                .thenThrow(new MizerException("unexpected token"));
        final TransferRequest req = reimport("E1", Collections.<String, String>emptyMap());
        expectInvalid("version \"6.1\"\n(0010,0010) := \"X\"", req);
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private void expectInvalid(final String script, final TransferRequest req) {
        try {
            compiler.validateBatch(script, singletonList(req));
            fail("expected ScriptValidationException");
        } catch (ScriptValidationException expected) {
            // ok
        }
    }

    private static TransferRequest reimport(final String id, final Map<String, String> csvValues) {
        final TransferRequest req = new TransferRequest("DEST", id, TransferMode.REIMPORT);
        req.setCsvValues(csvValues);
        return req;
    }

    private static Map<String, String> csv(final String key, final String value) {
        final Map<String, String> m = new HashMap<>();
        m.put(key, value);
        return m;
    }
}
