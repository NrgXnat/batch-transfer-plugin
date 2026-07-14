package org.nrg.xnatx.plugins.transfer.service;

import org.apache.commons.lang3.StringUtils;
import org.nrg.xdat.model.XnatSubjectassessordataI;
import org.nrg.xdat.om.XnatSubjectdata;
import org.nrg.xft.security.UserI;
import org.nrg.xnatx.plugins.transfer.model.ManifestRow;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves each manifest row's {@code source_subject_label} + {@code source_session_label} to a session in the
 * source project, honoring the user's read permission. Synchronous, no persistence — the "light preflight" the
 * CSV-selection flow needs, not a job engine. Sets each row's {@code status} to {@code matched} (+
 * {@code resolvedId}), {@code subject_not_found}, or {@code session_not_found} (+ {@code availableSessions}).
 *
 * <p>Existence only: the row is matched when the subject has an experiment with that label. Reimportability
 * (image-session-only, assessor rejection) is re-checked at submit ({@code BatchTransferServiceImpl}), which is
 * the authoritative gate. Subjects are cached by label within a batch so repeated rows don't re-load.
 *
 * <p>The two XNAT calls are isolated in {@code protected} seams so the resolution logic is unit-testable
 * without XDAT/Spring wiring. A matched row carries no file count — resolution is existence only.
 */
@Service
public class PreflightResolver {

    /** Resolve every row in place, caching each subject's sessions by label for the span of this batch. */
    public void resolveAll(final String sourceProject, final List<ManifestRow> rows, final UserI user) {
        final Map<String, List<SessionRef>> cache = new HashMap<>();
        for (final ManifestRow row : rows) {
            resolve(sourceProject, row, user, cache);
        }
    }

    private void resolve(final String sourceProject, final ManifestRow row, final UserI user,
                         final Map<String, List<SessionRef>> cache) {
        final String subjectLabel = row.getSourceSubjectLabel();
        final String sessionLabel = row.getSourceSessionLabel();

        if (StringUtils.isBlank(subjectLabel)) {
            row.setStatus(ManifestRow.STATUS_SUBJECT_NOT_FOUND);
            return;
        }

        final List<SessionRef> sessions;
        if (cache.containsKey(subjectLabel)) {
            sessions = cache.get(subjectLabel);         // may be null = subject not found
        } else {
            final XnatSubjectdata subject = findSubject(sourceProject, subjectLabel, user);
            sessions = (subject == null) ? null : listSessions(subject);
            cache.put(subjectLabel, sessions);
        }

        if (sessions == null) {
            row.setStatus(ManifestRow.STATUS_SUBJECT_NOT_FOUND);
            return;
        }

        for (final SessionRef session : sessions) {
            if (session.label != null && session.label.equals(sessionLabel)) {
                row.setStatus(ManifestRow.STATUS_MATCHED);
                row.setResolvedId(session.id);
                return;
            }
        }

        row.setStatus(ManifestRow.STATUS_SESSION_NOT_FOUND);
        final List<String> labels = new ArrayList<>();
        for (final SessionRef session : sessions) {
            if (StringUtils.isNotBlank(session.label)) {
                labels.add(session.label);
            }
        }
        row.setAvailableSessions(labels);
    }

    /**
     * Resolve a subject by label within the project, honoring the user's read permission (returns null when
     * not found or not readable). Overridable so tests need no XDAT wiring.
     */
    protected XnatSubjectdata findSubject(final String project, final String label, final UserI user) {
        return XnatSubjectdata.GetSubjectByProjectIdentifier(project, label, user, false);
    }

    /** The subject's experiments as (id, label) pairs. Overridable so tests need no XDAT wiring. */
    protected List<SessionRef> listSessions(final XnatSubjectdata subject) {
        final List<SessionRef> sessions = new ArrayList<>();
        for (final XnatSubjectassessordataI experiment : subject.getExperiments_experiment()) {
            sessions.add(new SessionRef(experiment.getId(), experiment.getLabel()));
        }
        return sessions;
    }

    /** An experiment reference: its id and label. */
    protected static final class SessionRef {
        final String id;
        final String label;

        SessionRef(final String id, final String label) {
            this.id = id;
            this.label = label;
        }
    }
}
