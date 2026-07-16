package org.nrg.xnatx.plugins.transfer.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nrg.xdat.model.XnatSubjectassessordataI;
import org.nrg.xdat.om.XnatExperimentdata;
import org.nrg.xdat.om.XnatSubjectdata;
import org.nrg.xft.security.UserI;
import org.nrg.xnatx.plugins.transfer.model.ManifestRow;
import org.nrg.xnatx.plugins.transfer.util.XnatUtils;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
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
 * <p>The XNAT calls are isolated in {@code protected} seams so the resolution logic is unit-testable
 * without XDAT/Spring wiring. Resolution is existence only; a matched row carries a reimportable-DICOM
 * {@code fileCount} only when {@code includeFileCount} is requested (a display-only advisory number, computed
 * lazily per row on cohort-table expand — a filesystem walk, so it is skipped by default).
 */
@Slf4j
@Service
public class PreflightResolver {

    /** Resolve every row in place (no file counts). */
    public void resolveAll(final String sourceProject, final List<ManifestRow> rows, final UserI user) {
        resolveAll(sourceProject, rows, user, false);
    }

    /**
     * Resolve every row in place, caching each subject's sessions by label for the span of this batch. When
     * {@code includeFileCount} is true, each matched row also gets its reimportable-DICOM {@code fileCount}
     * (a filesystem walk per matched session — request it only for the rows that need it).
     */
    public void resolveAll(final String sourceProject, final List<ManifestRow> rows, final UserI user,
                           final boolean includeFileCount) {
        final Map<String, List<SessionRef>> cache = new HashMap<>();
        for (final ManifestRow row : rows) {
            resolve(sourceProject, row, user, cache, includeFileCount);
        }
    }

    private void resolve(final String sourceProject, final ManifestRow row, final UserI user,
                         final Map<String, List<SessionRef>> cache, final boolean includeFileCount) {
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
                if (includeFileCount) {
                    row.setFileCount(countReimportableFiles(session.id, user));
                }
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

    /**
     * The number of reimportable DICOM files in a matched session — the exact selection a Reimport zips
     * (mirrors {@code BatchTransferServiceImpl.StreamingZipFileWriter}: regular files with a {@code DICOM}
     * path component, excluding {@code catalog.xml}, under the SCANS tree of {@code getCurrentSessionFolder}).
     * Display-only: any failure (not an image session, unreadable tree) yields {@code null} rather than failing
     * the preflight. Overridable so tests need no XDAT/filesystem wiring.
     */
    protected Integer countReimportableFiles(final String experimentId, final UserI user) {
        try {
            final XnatExperimentdata experiment = XnatUtils.getExperiment(experimentId, user);
            if (experiment == null) {
                return null;
            }
            return countDicomFilesUnder(Paths.get(experiment.getCurrentSessionFolder(true)));
        } catch (Exception e) {
            log.warn("Could not compute reimportable file count for experiment {}", experimentId, e);
            return null;
        }
    }

    /**
     * Count regular DICOM files under {@code sourceDir}, descending only into the SCANS directory directly
     * beneath the root (as the reimport zip producer does), matching a {@code DICOM} path component and
     * excluding {@code catalog.xml}.
     */
    private static Integer countDicomFilesUnder(final Path sourceDir) throws IOException {
        if (sourceDir == null || !Files.isDirectory(sourceDir)) {
            return 0;
        }
        final int[] count = {0};
        Files.walkFileTree(sourceDir, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
                        if (sourceDir.equals(dir.getParent())
                                && !dir.getFileName().toString().equalsIgnoreCase("SCANS")) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
                        if (attrs.isRegularFile()
                                && hasPathComponent(file, "DICOM")
                                && !file.getFileName().toString().toLowerCase().endsWith("catalog.xml")) {
                            count[0]++;
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
        return count[0];
    }

    /** True if any component of {@code p} matches {@code component}, ignoring case. */
    private static boolean hasPathComponent(final Path p, final String component) {
        for (final Path part : p) {
            if (part.toString().equalsIgnoreCase(component)) {
                return true;
            }
        }
        return false;
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
