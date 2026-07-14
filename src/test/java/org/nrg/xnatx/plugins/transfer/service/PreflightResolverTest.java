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
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;

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

        StubResolver(final Map<String, List<SessionRef>> bySubjectLabel) {
            this.bySubjectLabel = bySubjectLabel;
        }

        @Override
        protected XnatSubjectdata findSubject(final String project, final String label, final UserI user) {
            findSubjectCalls++;
            if (!bySubjectLabel.containsKey(label)) {
                pending = null;
                return null;
            }
            pending = bySubjectLabel.get(label);   // consumed by the immediately-following listSessions()
            return mock(XnatSubjectdata.class);
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

    private static PreflightResolver.SessionRef session(final String id, final String label) {
        return new PreflightResolver.SessionRef(id, label);
    }

    @Test
    public void matchesAnExistingSession() {
        final StubResolver resolver = new StubResolver(
                Collections.singletonMap("S1", Arrays.asList(session("E1", "SESS1"))));
        final ManifestRow row = row("S1", "SESS1");

        resolver.resolveAll("PROJ", new ArrayList<>(Arrays.asList(row)), mock(UserI.class));

        assertEquals(ManifestRow.STATUS_MATCHED, row.getStatus());
        assertEquals("E1", row.getResolvedId());
        assertNull(row.getAvailableSessions());
    }

    @Test
    public void reportsSessionNotFoundWithAvailableSessions() {
        final StubResolver resolver = new StubResolver(Collections.singletonMap(
                "S1", Arrays.asList(session("E1", "SESS1"), session("E2", "SESS2"))));
        final ManifestRow row = row("S1", "NOPE");

        resolver.resolveAll("PROJ", new ArrayList<>(Arrays.asList(row)), mock(UserI.class));

        assertEquals(ManifestRow.STATUS_SESSION_NOT_FOUND, row.getStatus());
        assertNull(row.getResolvedId());
        assertEquals(Arrays.asList("SESS1", "SESS2"), row.getAvailableSessions());
    }

    @Test
    public void reportsSubjectNotFound() {
        final StubResolver resolver = new StubResolver(Collections.<String, List<PreflightResolver.SessionRef>>emptyMap());
        final ManifestRow row = row("MISSING", "SESS1");

        resolver.resolveAll("PROJ", new ArrayList<>(Arrays.asList(row)), mock(UserI.class));

        assertEquals(ManifestRow.STATUS_SUBJECT_NOT_FOUND, row.getStatus());
    }

    @Test
    public void aBlankSubjectLabelIsSubjectNotFoundWithoutAnyLookup() {
        final StubResolver resolver = new StubResolver(Collections.<String, List<PreflightResolver.SessionRef>>emptyMap());
        final ManifestRow row = row("", "SESS1");

        resolver.resolveAll("PROJ", new ArrayList<>(Arrays.asList(row)), mock(UserI.class));

        assertEquals(ManifestRow.STATUS_SUBJECT_NOT_FOUND, row.getStatus());
        assertEquals("no subject lookup for a blank label", 0, resolver.findSubjectCalls);
    }

    @Test
    public void cachesSubjectLookupAcrossRows() {
        final StubResolver resolver = new StubResolver(Collections.singletonMap(
                "S1", Arrays.asList(session("E1", "SESS1"), session("E2", "SESS2"))));
        final ManifestRow r1 = row("S1", "SESS1");
        final ManifestRow r2 = row("S1", "SESS2");

        resolver.resolveAll("PROJ", new ArrayList<>(Arrays.asList(r1, r2)), mock(UserI.class));

        assertEquals(ManifestRow.STATUS_MATCHED, r1.getStatus());
        assertEquals(ManifestRow.STATUS_MATCHED, r2.getStatus());
        assertEquals("the same subject is looked up only once per batch", 1, resolver.findSubjectCalls);
    }
}
