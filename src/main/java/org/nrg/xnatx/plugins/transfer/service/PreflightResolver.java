package org.nrg.xnatx.plugins.transfer.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nrg.xdat.model.XnatImagesessiondataI;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves each manifest row against the source project, honoring the user's read permission. Synchronous, no
 * persistence — the "light preflight" the CSV-selection flow needs, not a job engine.
 *
 * <ul>
 *   <li><b>Session row</b> → {@code matched} (+ {@code resolvedId}), {@code subject_not_found} or
 *   {@code session_not_found}. Existence only: the row is matched when the subject has an experiment with that
 *   label; reimportability (image-session-only, assessor rejection) is re-checked at submit
 *   ({@code BatchTransferServiceImpl}), which is the authoritative gate.</li>
 *   <li><b>Subject row</b> (blank session label) → one {@code matched} entry per image session of the subject,
 *   each a copy of the row with that session's label and id; {@code no_sessions} when the subject has none.</li>
 * </ul>
 * A row that already carries a status from parsing ({@code duplicate_session}) is passed through unresolved.
 * Subjects are cached by label within a batch so repeated rows don't re-load.
 *
 * <p>The XNAT calls are isolated in {@code protected} seams so the resolution logic is unit-testable
 * without XDAT/Spring wiring. A matched entry carries a reimportable-DICOM {@code fileCount} only when
 * {@code includeFileCount} is requested (a display-only advisory number, computed lazily on cohort-table
 * expand — a filesystem walk, so it is skipped by default).
 */
@Slf4j
@Service
public class PreflightResolver {

    /**
     * Resolve {@code rows} for a Reimport and return the resolved entries, in manifest order. A matched subject
     * row is replaced by its sessions, so the result can be longer than {@code rows}. When
     * {@code includeFileCount} is true, each matched session also gets its reimportable-DICOM {@code fileCount}
     * (a filesystem walk per session — request it only for the rows that need it).
     */
    public List<ManifestRow> resolveAll(final String sourceProject, final List<ManifestRow> rows, final UserI user,
                                        final boolean includeFileCount) {
        final Map<String, SubjectRef> cache = new HashMap<>();
        final List<ManifestRow> resolved = new ArrayList<>(rows.size());
        for (final ManifestRow row : rows) {
            if (row.getStatus() != null) {
                resolved.add(row);
                continue;
            }
            final String subjectLabel = row.getSourceSubjectLabel();
            if (StringUtils.isBlank(subjectLabel)) {
                row.setStatus(ManifestRow.STATUS_SUBJECT_NOT_FOUND);
                resolved.add(row);
                continue;
            }
            // containsKey, not computeIfAbsent: a subject that isn't found is cached as null too.
            if (!cache.containsKey(subjectLabel)) {
                cache.put(subjectLabel, lookUpSubject(sourceProject, subjectLabel, user));
            }
            final SubjectRef subject = cache.get(subjectLabel);
            if (subject == null) {
                row.setStatus(ManifestRow.STATUS_SUBJECT_NOT_FOUND);
                resolved.add(row);
            } else if (!row.isSubjectRow()) {
                resolveSession(row, subject, user, includeFileCount);
                resolved.add(row);
            } else {
                resolved.addAll(expandSubject(row, subject, user, includeFileCount));
            }
        }
        return resolved;
    }

    /** The subject with its sessions, or null when it isn't found or isn't readable. */
    private SubjectRef lookUpSubject(final String sourceProject, final String label, final UserI user) {
        final XnatSubjectdata subject = findSubject(sourceProject, label, user);
        return (subject == null) ? null : new SubjectRef(listSessions(subject));
    }

    private void resolveSession(final ManifestRow row, final SubjectRef subject, final UserI user,
                                final boolean includeFileCount) {
        for (final SessionRef session : subject.sessions) {
            if (session.label != null && session.label.equals(row.getSourceSessionLabel())) {
                row.setStatus(ManifestRow.STATUS_MATCHED);
                row.setResolvedId(session.id);
                if (includeFileCount) {
                    row.setFileCount(countReimportableFiles(session.id, user));
                }
                return;
            }
        }
        row.setStatus(ManifestRow.STATUS_SESSION_NOT_FOUND);
    }

    /** A subject row → one matched entry per image session (by label), or the row as {@code no_sessions}. */
    private List<ManifestRow> expandSubject(final ManifestRow row, final SubjectRef subject, final UserI user,
                                            final boolean includeFileCount) {
        final List<SessionRef> imageSessions = new ArrayList<>();
        for (final SessionRef session : subject.sessions) {
            if (session.imageSession) {
                imageSessions.add(session);
            }
        }
        if (imageSessions.isEmpty()) {
            row.setStatus(ManifestRow.STATUS_NO_SESSIONS);
            return Collections.singletonList(row);
        }
        imageSessions.sort(Comparator.comparing(session -> StringUtils.defaultString(session.label)));
        final List<ManifestRow> entries = new ArrayList<>(imageSessions.size());
        for (final SessionRef session : imageSessions) {
            final ManifestRow entry = copyOf(row);
            entry.setSourceSessionLabel(session.label);
            entry.setStatus(ManifestRow.STATUS_MATCHED);
            entry.setResolvedId(session.id);
            if (includeFileCount) {
                entry.setFileCount(countReimportableFiles(session.id, user));
            }
            entries.add(entry);
        }
        return entries;
    }

    /** A copy of a parsed row (its own value lists; the {@code csvValues} map is shared, read-only from here). */
    private static ManifestRow copyOf(final ManifestRow row) {
        final ManifestRow copy = new ManifestRow();
        copy.setIndex(row.getIndex());
        copy.setRowNumber(row.getRowNumber());
        copy.setSourceSubjectLabel(row.getSourceSubjectLabel());
        copy.setSourceSessionLabel(row.getSourceSessionLabel());
        copy.setSubjectRow(row.isSubjectRow());
        copy.setDestinationSubjectLabel(row.getDestinationSubjectLabel());
        copy.setDestinationSessionLabel(row.getDestinationSessionLabel());
        copy.setCsvValues(row.getCsvValues());
        copy.setValueErrors(new ArrayList<>(row.getValueErrors()));
        copy.setValueWarnings(new ArrayList<>(row.getValueWarnings()));
        return copy;
    }

    /**
     * Resolve a subject by label within the project, honoring the user's read permission (returns null when
     * not found or not readable). Overridable so tests need no XDAT wiring.
     */
    protected XnatSubjectdata findSubject(final String project, final String label, final UserI user) {
        return XnatSubjectdata.GetSubjectByProjectIdentifier(project, label, user, false);
    }

    /** The subject's experiments as (id, label, image session?) refs. Overridable so tests need no XDAT wiring. */
    protected List<SessionRef> listSessions(final XnatSubjectdata subject) {
        final List<SessionRef> sessions = new ArrayList<>();
        for (final XnatSubjectassessordataI experiment : subject.getExperiments_experiment()) {
            sessions.add(new SessionRef(experiment.getId(), experiment.getLabel(),
                    experiment instanceof XnatImagesessiondataI));
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

    /** An experiment reference: its id, its label, and whether it is an image session (Reimport's unit). */
    protected static final class SessionRef {
        final String  id;
        final String  label;
        final boolean imageSession;

        SessionRef(final String id, final String label, final boolean imageSession) {
            this.id = id;
            this.label = label;
            this.imageSession = imageSession;
        }
    }

    /** A found subject's experiments. */
    private static final class SubjectRef {
        final List<SessionRef> sessions;

        SubjectRef(final List<SessionRef> sessions) {
            this.sessions = sessions;
        }
    }
}
