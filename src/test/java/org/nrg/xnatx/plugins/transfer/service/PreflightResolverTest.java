package org.nrg.xnatx.plugins.transfer.service;

import org.junit.Test;
import org.nrg.xdat.om.XnatSubjectdata;
import org.nrg.xft.security.UserI;
import org.nrg.xnatx.plugins.transfer.model.ManifestRow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Resolution logic, with the two XNAT seams ({@code findSubject}/{@code listSessions}) stubbed from an
 * in-memory subject→sessions map — so no XDAT wiring is needed (mirrors the {@code TransferCapabilitiesService}
 * probe-seam approach).
 */
public class PreflightResolverTest {

    /** A resolver whose XNAT lookups are served from a {@code subjectLabel → sessions} map. */
    private static class StubResolver extends PreflightResolver {
        private final Map<String, List<SessionRef>> bySubjectLabel;
        private List<SessionRef> pending;
        int findSubjectCalls = 0;
        int fileCountCalls = 0;
        String lastFileCountExperimentId;
        Integer stubbedFileCount = 42;

        StubResolver(final Map<String, List<SessionRef>> bySubjectLabel) {
            this.bySubjectLabel = bySubjectLabel;
        }

        @Override
        protected Integer countReimportableFiles(final String experimentId, final UserI user) {
            fileCountCalls++;
            lastFileCountExperimentId = experimentId;
            return stubbedFileCount;
        }

        @Override
        protected XnatSubjectdata findSubject(final String project, final String label, final UserI user) {
            findSubjectCalls++;
            if (!bySubjectLabel.containsKey(label)) {
                pending = null;
                return null;
            }
            pending = bySubjectLabel.get(label);   // consumed by the immediately-following listSessions()
            final XnatSubjectdata subject = mock(XnatSubjectdata.class);
            when(subject.getId()).thenReturn("SUBJ_" + label);
            return subject;
        }

        @Override
        protected List<SessionRef> listSessions(final XnatSubjectdata subject) {
            return pending;
        }
    }

    private static ManifestRow row(final String subjectLabel, final String sessionLabel) {
        final ManifestRow row = new ManifestRow();
        row.setSourceSubjectLabel(subjectLabel);
        row.setSourceSessionLabel(sessionLabel);
        return row;
    }

    private static ManifestRow subjectRow(final int index, final String subjectLabel) {
        final ManifestRow row = row(subjectLabel, "");
        row.setIndex(index);
        row.setSubjectRow(true);
        return row;
    }

    private static PreflightResolver.SessionRef session(final String id, final String label) {
        return new PreflightResolver.SessionRef(id, label, true);
    }

    private static PreflightResolver.SessionRef nonImage(final String id, final String label) {
        return new PreflightResolver.SessionRef(id, label, false);
    }

    private static List<ManifestRow> resolve(final StubResolver resolver, final boolean fileCount,
                                             final ManifestRow... rows) {
        return resolver.resolveAll("PROJ", new ArrayList<>(Arrays.asList(rows)), mock(UserI.class), fileCount);
    }

    @Test
    public void matchesAnExistingSession() {
        final StubResolver resolver = new StubResolver(
                Collections.singletonMap("S1", Arrays.asList(session("E1", "SESS1"))));
        final ManifestRow row = row("S1", "SESS1");

        resolver.resolveAll("PROJ", new ArrayList<>(Arrays.asList(row)), mock(UserI.class), false);

        assertEquals(ManifestRow.STATUS_MATCHED, row.getStatus());
        assertEquals("E1", row.getResolvedId());
    }

    @Test
    public void reportsSessionNotFound() {
        final StubResolver resolver = new StubResolver(Collections.singletonMap(
                "S1", Arrays.asList(session("E1", "SESS1"), session("E2", "SESS2"))));
        final ManifestRow row = row("S1", "NOPE");

        resolver.resolveAll("PROJ", new ArrayList<>(Arrays.asList(row)), mock(UserI.class), false);

        assertEquals(ManifestRow.STATUS_SESSION_NOT_FOUND, row.getStatus());
        assertNull(row.getResolvedId());
    }

    @Test
    public void reportsSubjectNotFound() {
        final StubResolver resolver = new StubResolver(Collections.<String, List<PreflightResolver.SessionRef>>emptyMap());
        final ManifestRow row = row("MISSING", "SESS1");

        resolver.resolveAll("PROJ", new ArrayList<>(Arrays.asList(row)), mock(UserI.class), false);

        assertEquals(ManifestRow.STATUS_SUBJECT_NOT_FOUND, row.getStatus());
    }

    @Test
    public void aBlankSubjectLabelIsSubjectNotFoundWithoutAnyLookup() {
        final StubResolver resolver = new StubResolver(Collections.<String, List<PreflightResolver.SessionRef>>emptyMap());
        final ManifestRow row = row("", "SESS1");

        resolver.resolveAll("PROJ", new ArrayList<>(Arrays.asList(row)), mock(UserI.class), false);

        assertEquals(ManifestRow.STATUS_SUBJECT_NOT_FOUND, row.getStatus());
        assertEquals("no subject lookup for a blank label", 0, resolver.findSubjectCalls);
    }

    @Test
    public void cachesSubjectLookupAcrossRows() {
        final StubResolver resolver = new StubResolver(Collections.singletonMap(
                "S1", Arrays.asList(session("E1", "SESS1"), session("E2", "SESS2"))));
        final ManifestRow r1 = row("S1", "SESS1");
        final ManifestRow r2 = row("S1", "SESS2");

        resolver.resolveAll("PROJ", new ArrayList<>(Arrays.asList(r1, r2)), mock(UserI.class), false);

        assertEquals(ManifestRow.STATUS_MATCHED, r1.getStatus());
        assertEquals(ManifestRow.STATUS_MATCHED, r2.getStatus());
        assertEquals("the same subject is looked up only once per batch", 1, resolver.findSubjectCalls);
    }

    @Test
    public void computesFileCountForMatchedRowWhenRequested() {
        final StubResolver resolver = new StubResolver(
                Collections.singletonMap("S1", Arrays.asList(session("E1", "SESS1"))));
        final ManifestRow row = row("S1", "SESS1");

        resolver.resolveAll("PROJ", new ArrayList<>(Arrays.asList(row)), mock(UserI.class), true);

        assertEquals(ManifestRow.STATUS_MATCHED, row.getStatus());
        assertEquals(Integer.valueOf(42), row.getFileCount());
        assertEquals(1, resolver.fileCountCalls);
        assertEquals("counts the resolved experiment", "E1", resolver.lastFileCountExperimentId);
    }

    @Test
    public void skipsFileCountByDefault() {
        final StubResolver resolver = new StubResolver(
                Collections.singletonMap("S1", Arrays.asList(session("E1", "SESS1"))));
        final ManifestRow row = row("S1", "SESS1");

        resolver.resolveAll("PROJ", new ArrayList<>(Arrays.asList(row)), mock(UserI.class), false);

        assertEquals(ManifestRow.STATUS_MATCHED, row.getStatus());
        assertNull("no file count without the flag", row.getFileCount());
        assertEquals("the count seam is never walked by default", 0, resolver.fileCountCalls);
    }

    @Test
    public void doesNotCountFilesForUnmatchedRowEvenWhenRequested() {
        final StubResolver resolver = new StubResolver(
                Collections.singletonMap("S1", Arrays.asList(session("E1", "SESS1"))));
        final ManifestRow row = row("S1", "NOPE");

        resolver.resolveAll("PROJ", new ArrayList<>(Arrays.asList(row)), mock(UserI.class), true);

        assertEquals(ManifestRow.STATUS_SESSION_NOT_FOUND, row.getStatus());
        assertNull(row.getFileCount());
        assertEquals(0, resolver.fileCountCalls);
    }

    // ---- subject rows ----------------------------------------------------------------------------------

    @Test
    public void subjectRowExpandsToItsImageSessionsByLabel() {
        final StubResolver resolver = new StubResolver(Collections.singletonMap("S1",
                Arrays.asList(session("E2", "SESS_B"), nonImage("E3", "QC_FORM"), session("E1", "SESS_A"))));
        final ManifestRow row = subjectRow(3, "S1");
        row.setRowNumber(4);
        row.setCsvValues(Collections.singletonMap("pid", "ANON-1"));
        row.setDestinationSubjectLabel("ANON_S1");
        row.getValueWarnings().add("a warning");

        final List<ManifestRow> resolved = resolve(resolver, false, row);

        assertEquals("one entry per image session; the non-image experiment is left out", 2, resolved.size());
        assertEquals("SESS_A", resolved.get(0).getSourceSessionLabel());
        assertEquals("E1", resolved.get(0).getResolvedId());
        assertEquals("SESS_B", resolved.get(1).getSourceSessionLabel());
        assertEquals("E2", resolved.get(1).getResolvedId());
        for (final ManifestRow entry : resolved) {
            assertEquals(ManifestRow.STATUS_MATCHED, entry.getStatus());
            assertEquals("keeps the manifest row it came from", 3, entry.getIndex());
            assertEquals(4, entry.getRowNumber());
            assertTrue(entry.isSubjectRow());
            assertEquals("S1", entry.getSourceSubjectLabel());
            assertEquals("ANON-1", entry.getCsvValues().get("pid"));
            assertEquals("ANON_S1", entry.getDestinationSubjectLabel());
            assertEquals(Arrays.asList("a warning"), entry.getValueWarnings());
        }
        assertNotSame("each entry has its own warning list",
                resolved.get(0).getValueWarnings(), resolved.get(1).getValueWarnings());
    }

    @Test
    public void subjectRowWithNoImageSessionsIsNoSessions() {
        final StubResolver resolver = new StubResolver(
                Collections.singletonMap("S1", Arrays.asList(nonImage("E3", "QC_FORM"))));
        final ManifestRow row = subjectRow(1, "S1");

        final List<ManifestRow> resolved = resolve(resolver, false, row);

        assertEquals(1, resolved.size());
        assertSame(row, resolved.get(0));
        assertEquals(ManifestRow.STATUS_NO_SESSIONS, row.getStatus());
        assertNull(row.getResolvedId());
    }

    @Test
    public void subjectRowForAMissingSubjectIsSubjectNotFound() {
        final StubResolver resolver = new StubResolver(Collections.<String, List<PreflightResolver.SessionRef>>emptyMap());
        final ManifestRow row = subjectRow(1, "MISSING");

        final List<ManifestRow> resolved = resolve(resolver, false, row);

        assertEquals(1, resolved.size());
        assertEquals(ManifestRow.STATUS_SUBJECT_NOT_FOUND, row.getStatus());
    }

    @Test
    public void aRowFlaggedWhileParsingIsPassedThroughWithoutALookup() {
        final StubResolver resolver = new StubResolver(
                Collections.singletonMap("S1", Arrays.asList(session("E1", "SESS1"))));
        final ManifestRow subject = subjectRow(1, "S1");
        subject.setStatus(ManifestRow.STATUS_DUPLICATE_SESSION);
        final ManifestRow session = row("S1", "SESS1");
        session.setStatus(ManifestRow.STATUS_DUPLICATE_SESSION);

        final List<ManifestRow> resolved = resolve(resolver, false, subject, session);

        assertEquals(2, resolved.size());
        assertEquals(ManifestRow.STATUS_DUPLICATE_SESSION, resolved.get(0).getStatus());
        assertEquals(ManifestRow.STATUS_DUPLICATE_SESSION, resolved.get(1).getStatus());
        assertNull(resolved.get(1).getResolvedId());
        assertEquals(0, resolver.findSubjectCalls);
    }

    @Test
    public void countsFilesForEachExpandedSessionWhenRequested() {
        final StubResolver resolver = new StubResolver(Collections.singletonMap("S1",
                Arrays.asList(session("E1", "SESS_A"), session("E2", "SESS_B"))));

        final List<ManifestRow> resolved = resolve(resolver, true, subjectRow(1, "S1"));

        assertEquals(Integer.valueOf(42), resolved.get(0).getFileCount());
        assertEquals(Integer.valueOf(42), resolved.get(1).getFileCount());
        assertEquals(2, resolver.fileCountCalls);
    }

    @Test
    public void aMissingSubjectIsLookedUpOnlyOnce() {
        final StubResolver resolver = new StubResolver(Collections.<String, List<PreflightResolver.SessionRef>>emptyMap());

        resolve(resolver, false, row("MISSING", "SESS1"), row("MISSING", "SESS2"));

        assertEquals(1, resolver.findSubjectCalls);
    }
}
