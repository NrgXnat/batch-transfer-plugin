package org.nrg.xnatx.plugins.transfer.service.impl;

import org.nrg.xnatx.plugins.transfer.event.BatchTransferEvent;
import org.nrg.xnatx.plugins.transfer.service.BatchTransferService;
import org.nrg.xnatx.plugins.transfer.util.FileUtil;
import org.nrg.xnatx.plugins.transfer.util.XnatUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.nrg.framework.services.ContextService;
import org.nrg.framework.services.NrgEventService;
import org.nrg.xdat.model.XnatAbstractresourceI;
import org.nrg.xdat.model.XnatImagescandataI;
import org.nrg.xdat.model.XnatReconstructedimagedataI;
import org.nrg.xdat.model.XnatSubjectassessordataI;
import org.nrg.xdat.om.*;
import org.nrg.xdat.security.helpers.Features;
import org.nrg.xdat.security.helpers.Permissions;
import org.nrg.xft.ItemI;
import org.nrg.xft.event.EventDetails;
import org.nrg.xft.event.EventUtils;
import org.nrg.xft.security.UserI;
import org.nrg.xft.utils.FileUtils;
import org.nrg.xft.utils.SaveItemHelper;
import org.nrg.xnat.DicomObjectIdentifier;
import org.nrg.xnat.archive.DicomZipImporter;
import org.nrg.xnat.archive.Operation;
import org.nrg.xnat.helpers.merge.AnonUtils;
import org.nrg.xnat.helpers.prearchive.PrearcSession;
import org.nrg.xnat.helpers.prearchive.PrearcUtils;
import org.nrg.xnat.helpers.uri.URIManager;
import org.nrg.xnat.restlet.util.FileWriterWrapperI;
import org.nrg.xnat.services.messaging.prearchive.PrearchiveOperationRequest;
import org.nrg.xnat.turbine.utils.ArchivableItem;
import org.nrg.xnatx.plugins.transfer.model.BatchTransfer;
import org.nrg.xnatx.plugins.transfer.model.TransferMode;
import org.nrg.xnatx.plugins.transfer.model.EventInfo;
import org.nrg.xnatx.plugins.transfer.model.Fields;
import org.nrg.xnatx.plugins.transfer.model.TransferRequest;
import org.nrg.xnatx.plugins.transfer.jms.requests.TransferItemRequest;
import org.nrg.xnatx.plugins.transfer.jms.requests.CloneSubjectRequest;
import org.nrg.xnatx.plugins.transfer.jms.tasks.BatchTransferMonitor;
import org.nrg.xdat.XDAT;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@Slf4j
public class BatchTransferServiceImpl implements BatchTransferService {
    private final NrgEventService eventService;
    private final ExecutorService executorService;
    private final AnonUtils anonUtils;
    private final DicomObjectIdentifier identifier;
    private final BatchTransferMonitor monitor;

    @Autowired
    public BatchTransferServiceImpl(final NrgEventService eventService,
                                 final ExecutorService executorService,
                                 final AnonUtils anonUtils,
                                 final ContextService contextService,
                                 final BatchTransferMonitor monitor) {
        this.eventService = eventService;
        this.executorService = executorService;
        this.anonUtils = anonUtils;
        this.identifier = contextService.getBean("dicomObjectIdentifier", DicomObjectIdentifier.class);
        this.monitor = monitor;
    }

    public void submitTransferRequest(BatchTransfer batchTransferRequest, UserI user) {
        if (batchTransferRequest.getRequests() == null || batchTransferRequest.getRequests().size() == 0) {
            return;
        }

        if (StringUtils.isBlank(batchTransferRequest.getTrackingId())) {
            batchTransferRequest.setTrackingId(String.format("batch_transfer_%s", System.currentTimeMillis()));
        }

        final String queuedMsg = String.format("Transfer Queued (%s items)", batchTransferRequest.getRequests().size());
        eventService.triggerEvent(BatchTransferEvent.waiting(user.getID(), batchTransferRequest.getTrackingId(), queuedMsg));
        executorService.submit(() -> batchTransfer(batchTransferRequest, user));
    }

    private void batchTransfer(BatchTransfer batchTransferRequest, UserI user) {
        final List<TransferRequest> requests = batchTransferRequest.getRequests();
        if (requests == null || requests.isEmpty()) {
            return;
        }
        final String trackingId = batchTransferRequest.getTrackingId();

        // A batch usually carries a single operation, but the UI can mix them. Split by operation in
        // one pass so each item takes the right path: Reimport fans out to a JMS queue per item; Clone
        // fans out to a JMS queue per subject (one consumer owns each subject); Share (and any
        // null/unknown mode) is processed in-process on the sequential path.
        final List<TransferRequest> reimportItems   = new ArrayList<>();
        final List<TransferRequest> cloneItems      = new ArrayList<>();
        final List<TransferRequest> sequentialItems = new ArrayList<>();
        for (final TransferRequest request : requests) {
            final TransferMode mode = request.getMode();
            if (mode == TransferMode.REIMPORT) {
                reimportItems.add(request);
            } else if (mode == TransferMode.CLONE) {
                cloneItems.add(request);
            } else {
                sequentialItems.add(request);
            }
        }

        // Register the whole batch once; every item — whether queued (Reimport/Clone) or processed
        // in-process (Share) — reports to the monitor, which emits the single terminal event after the
        // last one finishes. This keeps one completion signal even when operations are mixed.
        monitor.register(trackingId, user.getID(), requests.size());
        if (!reimportItems.isEmpty()) {
            enqueueReimport(trackingId, reimportItems, user);
        }
        if (!cloneItems.isEmpty()) {
            enqueueClone(trackingId, cloneItems, user);
        }
        if (!sequentialItems.isEmpty()) {
            runSequential(trackingId, sequentialItems, user);
        }
    }

    /**
     * Producer for the parallel Reimport path. Enqueues one {@link TransferItemRequest} per item;
     * {@code TransferReimportListener} consumes them concurrently and each reports completion to the
     * {@link BatchTransferMonitor}. The batch is registered with the monitor once by the caller
     * ({@link #batchTransfer}), so this only enqueues; it runs on the executor thread, off the HTTP
     * path. Each session's own workflow is created (and completed/failed) inside {@code importExperiment}.
     */
    private void enqueueReimport(final String trackingId, final List<TransferRequest> requests, final UserI user) {
        for (final TransferRequest request : requests) {
            try {
                XDAT.sendJmsRequest(new TransferItemRequest(trackingId, request.getId(),
                        request.getDestinationProject(), user.getUsername(), user.getID(),
                        request.getPreserveSubjectLabel(), request.getPreserveSessionLabel()));
            } catch (Exception e) {
                // Couldn't enqueue this item — report it as a failed completion so the batch still finishes.
                log.error("Failed to queue reimport for {}", request.getId(), e);
                eventService.triggerEvent(BatchTransferEvent.fail(user.getID(), 0, trackingId,
                        request.getId() + " could not be queued. Cause: " + e.getMessage()));
                monitor.itemDone(trackingId, true);
            }
        }
    }

    /**
     * Producer for the parallel Clone path. Groups the items by their subject and enqueues one
     * {@link CloneSubjectRequest} per subject, so {@code CloneSubjectListener} processes each subject's
     * items serially in a single consumer (creating the destination subject/experiment once, with no
     * get-or-create race). Parallelism is across subjects. The batch is registered with the monitor once
     * by the caller ({@link #batchTransfer}); each item reports completion via its consumer.
     */
    private void enqueueClone(final String trackingId, final List<TransferRequest> requests, final UserI user) {
        final Map<String, List<CloneSubjectRequest.CloneItem>> bySubject = new LinkedHashMap<>();
        for (final TransferRequest request : requests) {
            try {
                final String subjectId = resolveSubjectId(request.getId());
                bySubject.computeIfAbsent(subjectId, k -> new ArrayList<>())
                        .add(new CloneSubjectRequest.CloneItem(request.getId(), request.getDestinationProject()));
            } catch (Exception e) {
                // Couldn't determine the subject — report this item failed so the batch still finishes.
                log.error("Failed to group clone item {}", request.getId(), e);
                eventService.triggerEvent(BatchTransferEvent.fail(user.getID(), 0, trackingId,
                        request.getId() + " could not be queued. Cause: " + e.getMessage()));
                monitor.itemDone(trackingId, true);
            }
        }

        for (final Map.Entry<String, List<CloneSubjectRequest.CloneItem>> entry : bySubject.entrySet()) {
            try {
                XDAT.sendJmsRequest(new CloneSubjectRequest(trackingId, entry.getKey(), entry.getValue(),
                        user.getUsername(), user.getID()));
            } catch (Exception e) {
                // Couldn't enqueue this subject's bundle — report all of its items failed.
                log.error("Failed to queue clone bundle for subject {}", entry.getKey(), e);
                for (final CloneSubjectRequest.CloneItem item : entry.getValue()) {
                    eventService.triggerEvent(BatchTransferEvent.fail(user.getID(), 0, trackingId,
                            item.getItemId() + " could not be queued. Cause: " + e.getMessage()));
                    monitor.itemDone(trackingId, true);
                }
            }
        }
    }

    /** Resolves an item's subject id for Clone grouping: subject → itself; experiment → its subject; assessor → its session's subject. */
    private String resolveSubjectId(final String itemId) throws Exception {
        final ArchivableItem item = XnatUtils.getArchivableItem(itemId, null);
        final String subjectId;
        if (item instanceof XnatSubjectdata) {
            subjectId = item.getId();
        } else if (item instanceof XnatImageassessordata) {
            subjectId = (String) ((XnatImageassessordata) item).getImageSessionData().getProperty("subject_ID");
        } else if (item instanceof XnatExperimentdata) {
            subjectId = (String) ((XnatExperimentdata) item).getProperty("subject_ID");
        } else {
            subjectId = null;
        }
        if (StringUtils.isBlank(subjectId)) {
            throw new Exception("Could not determine the subject for " + itemId);
        }
        return subjectId;
    }

    /**
     * In-process path for Share (and any null/unknown-mode) items. Calls {@link #processItem} per item
     * and reports each one to the {@link BatchTransferMonitor}; the monitor (not this method) emits the
     * batch's single terminal event once every item across all operations has finished — so this path
     * composes with the parallel Reimport/Clone queues in a mixed batch.
     */
    private void runSequential(final String trackingId, final List<TransferRequest> requests, final UserI user) {
        for (final TransferRequest request : requests) {
            final String modeAction = request.getMode() != null ? request.getMode().getAction() : "UNKNOWN MODE";
            final int progress = monitor.currentPercent(trackingId);
            boolean failed = false;
            try {
                eventService.triggerEvent(BatchTransferEvent.progress(user.getID(), progress, trackingId,
                        String.format("%s %s into project: %s", modeAction, request.getId(), request.getDestinationProject())));
                processItem(request, user, new EventInfo(trackingId, progress));
            } catch (Exception e) {
                failed = true;
                log.debug(e.getMessage(), e);
                final String err = String.format("%s %s failed. Cause: %s", request.getId(), request.getMode(), e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
                eventService.triggerEvent(BatchTransferEvent.fail(user.getID(), progress, trackingId, err));
            }
            // Report completion; the monitor emits the batch's terminal event when the last item finishes.
            monitor.itemDone(trackingId, failed);
        }
    }

    /**
     * Validates, loads, permission-checks, and dispatches a single transfer item by mode. Shared by
     * the sequential path ({@link #runSequential}) and the JMS consumers, so it is the single source
     * of truth for per-item transfer logic. Throws on any failure; the caller records it (a fail
     * event in the sequential path, a failed workflow + fail event in the consumer).
     */
    @Override
    public void processItem(final TransferRequest request, final UserI user, final EventInfo eventInfo) throws Exception {
        validateRequest(request);
        final XnatProjectdata destinationProjectData = XnatUtils.getProject(request.getDestinationProject(), user);
        final ArchivableItem item = XnatUtils.getArchivableItem(request.getId(), null);

        // We retrieve the item outside of the user's context so we can access archive path, etc.,
        // so we carefully check our permissions on it here.
        if (!Permissions.canRead(user, item)) {
            throw new Exception("You do not have permission to read " + item.getId());
        }

        final String sourceProject = item.getProject();
        if (sourceProject.equals(request.getDestinationProject())) {
            throw new Exception("Destination project cannot be the same as the source project.");
        }

        if (request.getMode() == TransferMode.SHARE) {
            if (!Features.checkRestrictedFeature(user, item.getProject(), Features.PROJECT_SHARING_FEATURE)) {
                throw new Exception("You do not have permission to share this data.");
            }
        } else {
            if (!Features.checkRestrictedFeature(user, item.getProject(), Fields.PROJECT_COPYING_FEATURE)) {
                throw new Exception("You do not have permission to clone this data.");
            }
        }

        if (!Permissions.canCreate(user, item.getXSIType() + "/project", destinationProjectData.getId())) {
            throw new Exception("You do not have permission to create " + item.getXSIType() + " in " +
                    destinationProjectData.getId());
        }

        if (item instanceof XnatSubjectdata) {
            final XnatSubjectdata sourceSubject = (XnatSubjectdata) item;
            final XnatSubjectdata existingSubject = XnatSubjectdata.GetSubjectByProjectIdentifier(destinationProjectData.getId(),
                    sourceSubject.getLabel(), null, false);

            if (request.getMode().equals(TransferMode.SHARE)) {
                shareSubject(sourceSubject, existingSubject, destinationProjectData, user, eventInfo);
            } else if (request.getMode().equals(TransferMode.CLONE)) {
                getOrCopySubject(sourceSubject, existingSubject, destinationProjectData, user, eventInfo);
            } else if (request.getMode().equals(TransferMode.REIMPORT)) {
                log.warn("Reimport operation is not supported for subjects.");
            } else {
                throw new Exception(String.format("Unsupported mode %s", request.getMode()));
            }
        } else if (item instanceof XnatExperimentdata) {
            final XnatExperimentdata sourceExperiment = (XnatExperimentdata) item;
            // Reimport does not use the existing-experiment lookup; skip that DB query for it.
            final XnatExperimentdata existingExperiment = (request.getMode() == TransferMode.REIMPORT) ? null
                    : XnatExperimentdata.GetExptByProjectIdentifier(destinationProjectData.getId(), sourceExperiment.getLabel(), null, false);

            if (request.getMode().equals(TransferMode.SHARE)) {
                shareExperiment(sourceExperiment, existingExperiment, destinationProjectData, user, eventInfo);
            } else if (request.getMode().equals(TransferMode.CLONE)) {
                getOrCopyExperimentOrAssessor(sourceExperiment, existingExperiment, destinationProjectData, user, eventInfo);
            } else if (request.getMode().equals(TransferMode.REIMPORT)) {
                importExperiment(sourceExperiment, destinationProjectData, user, eventInfo,
                        request.getPreserveSubjectLabel(),
                        request.getPreserveSessionLabel());
            } else {
                throw new Exception(String.format("Unsupported mode %s", request.getMode()));
            }
        } else {
            throw new Exception(String.format("Unsupported xsiType %s", item.getXSIType()));
        }
    }

    private void shareExperiment(XnatExperimentdata sourceExperiment, XnatExperimentdata existingExperiment, XnatProjectdata destinationProjectData, UserI user, EventInfo eventInfo) throws Exception {
        if (existingExperiment != null) {
            throwForLabelConflictExperiment(sourceExperiment, existingExperiment, destinationProjectData, user, eventInfo);
        } else {
            XnatUtils.shareExperimentToProject(destinationProjectData, sourceExperiment, user);
        }
    }

    private void shareSubject(XnatSubjectdata sourceSubject, XnatSubjectdata existingSubject, XnatProjectdata destinationProjectData, UserI user, EventInfo eventInfo) throws Exception {
        if (existingSubject != null) {
            throwForLabelConflictSubject(sourceSubject, existingSubject, destinationProjectData, user, eventInfo);
        } else {
            XnatUtils.shareSubjectToProject(destinationProjectData, sourceSubject, user);
        }
    }

    private void importExperiment(XnatExperimentdata sourceExperiment, XnatProjectdata destinationProjectData, UserI user, EventInfo eventInfo,
                                  Boolean preserveSubjectLabel, Boolean preserveSessionLabel) throws Exception {
        if (sourceExperiment instanceof XnatImageassessordata) {
            throw new Exception("Reimport operation is not supported for assessors.");
        }
        final Path sourcePath = Paths.get(sourceExperiment.getCurrentSessionFolder(true));
        final Map<String, Object> params = new HashMap<>();
        params.put("Ignore-Unparsable", "true");
        params.put("rename", "true");
        params.put("project", destinationProjectData.getId());

        // Preserve the source XNAT labels instead of letting the destination derive them from DICOM
        // tags. If the user asked to preserve a label but it is blank or missing, there is nothing
        // usable to override with, so fail the item rather than silently skipping the substitution.
        if (Boolean.TRUE.equals(preserveSubjectLabel)) {
            final XnatSubjectdata sourceSubject = XnatUtils.getSubject((String) sourceExperiment.getProperty("subject_ID"), user);
            final String subjectLabel = sourceSubject.getLabel();
            if (StringUtils.isBlank(subjectLabel)) {
                throw new Exception("Cannot preserve the source subject label for " + sourceExperiment.getId()
                        + ": the source subject label is blank or missing.");
            }
            params.put("subject", subjectLabel);
        }
        if (Boolean.TRUE.equals(preserveSessionLabel)) {
            final String sessionLabel = sourceExperiment.getLabel();
            if (StringUtils.isBlank(sessionLabel)) {
                throw new Exception("Cannot preserve the source session label for " + sourceExperiment.getId()
                        + ": the source session label is blank or missing.");
            }
            params.put("session", sessionLabel);
        }

        final StreamingZipFileWriter wrapper = new StreamingZipFileWriter(sourcePath, sourceExperiment.getId() + ".zip");
        final AtomicReference<List<String>> uriRef = new AtomicReference<>();
        XnatUtils.doActionWithWorkflow(user, sourceExperiment,
                sourceWorkflowLabel(TransferMode.REIMPORT, sourceExperiment.getLabel(), destinationProjectData.getId()), () -> {
            final List<String> result = runImporter(user, wrapper, params);
            if (result == null || result.isEmpty()) {
                throw new Exception("No DICOM files found in source experiment " + sourceExperiment.getId() + "; nothing to reimport.");
            }
            uriRef.set(result);
            return true;
        });
        final List<String> uris = uriRef.get();
        if (log.isDebugEnabled()) {
            final StringBuilder message = new StringBuilder("Processed ").append(uris.size()).append(" URIs:\n");
            for (final String uri : uris) {
                message.append(" * ").append(uri).append("\n");
            }
            log.debug(message.toString());
        }
        commitUris(uris, params, user);
    }

    /** How often the heartbeat thread logs while {@code importer.call()} is running. */
    static final long IMPORTER_HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000L;

    /**
     * Runs a {@link DicomZipImporter} against the given streaming wrapper and
     * returns the prearchive URIs it produced. Extracted as a protected seam
     * so unit tests can stub importer behaviour without loading
     * DicomZipImporter (whose supertype static initialisers require
     * XDAT/Spring wiring). On exit, drains the streaming producer so any
     * producer-side error surfaces here instead of being swallowed.
     *
     * <p>A daemon heartbeat thread logs at INFO every
     * {@link #IMPORTER_HEARTBEAT_INTERVAL_MS}ms while the importer is running,
     * so a stuck import is visible in {@code batch-transfer.log} instead of
     * being a silent hang.
     */
    protected List<String> runImporter(final UserI user, final FileWriterWrapperI wrapper, final Map<String, Object> params) throws Exception {
        final Thread heartbeat = startImporterHeartbeat(wrapper.getName());
        List<String> result = null;
        Exception importerError = null;
        try (DicomZipImporter importer = new DicomZipImporter(null, user, wrapper, params)) {
            importer.setIdentifier(this.identifier);
            result = importer.call();
        } catch (Exception e) {
            importerError = e;
        } finally {
            heartbeat.interrupt();
            if (wrapper instanceof StreamingZipFileWriter) {
                final StreamingZipFileWriter s = (StreamingZipFileWriter) wrapper;
                s.shutdown();
                try {
                    s.awaitProducer(30_000L);
                } catch (IOException producerError) {
                    // The importer's own failure is the user-facing cause; a producer-side error
                    // (genuine source truncation) must never mask it — attach it as suppressed.
                    if (importerError == null) {
                        importerError = producerError;
                    } else {
                        importerError.addSuppressed(producerError);
                    }
                }
            }
        }
        if (importerError != null) {
            throw importerError;
        }
        return result;
    }

    private static Thread startImporterHeartbeat(final String name) {
        final long startMs = System.currentTimeMillis();
        final Thread t = new Thread(() -> {
            try {
                while (true) {
                    Thread.sleep(IMPORTER_HEARTBEAT_INTERVAL_MS);
                    final long elapsedMin = (System.currentTimeMillis() - startMs) / 60_000L;
                    log.info("DicomZipImporter for {} still running after {} min", name, elapsedMin);
                }
            } catch (InterruptedException ignored) {
                // expected — interrupted by runImporter's finally when the importer returns
            }
        }, "batch-transfer-importer-heartbeat-" + name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void commitUris(List<String> uris, Map<String, Object> parameters, UserI user) throws Exception {
        for (final String uri : uris) {
            final Map<String, Object> properties = PrearcUtils.parseURI(uri);
            final String              project    = (String) properties.get(URIManager.PROJECT_ID);
            final String              timestamp  = (String) properties.get(PrearcUtils.PREARC_TIMESTAMP);
            final String              session    = (String) properties.get(PrearcUtils.PREARC_SESSION_FOLDER);

            final Map<String, Object> rebuildParameters = new HashMap<>(parameters);
            rebuildParameters.put(URIManager.PROJECT_ID, project);
            rebuildParameters.put(PrearcUtils.PREARC_TIMESTAMP, timestamp);
            rebuildParameters.put(PrearcUtils.PREARC_SESSION_FOLDER, session);
            //rebuildParameters.put(DicomInboxImportRequest.IMPORT_REQUEST_ID, request.getId());

            PrearcUtils.queuePrearchiveOperation(new PrearchiveOperationRequest(user, Operation.Rebuild, new PrearcSession(project, timestamp, session, rebuildParameters, user)));
        }
    }

    /**
     * Streams a DICOM zip of {@code sourceDir} into {@link DicomZipImporter}
     * without writing the zip to disk.
     *
     * <p>A daemon producer thread, started lazily on the first (and only)
     * call to {@link #getInputStream()}, walks the source tree, filters for
     * DICOM files (skipping {@code catalog.xml}), zips entries into a
     * {@link PipedOutputStream}, and the importer reads from the paired
     * {@link PipedInputStream}. Producer errors are captured into an
     * {@link AtomicReference} and surfaced by {@link #awaitProducer(long)},
     * which the consumer must call after the importer returns so that silent
     * truncation cannot masquerade as a successful import.
     *
     * <p>Path iteration is intentionally lazy — never collected to a list —
     * so memory does not scale with file count.
     */
    static final class StreamingZipFileWriter implements FileWriterWrapperI, AutoCloseable {
        private final Path sourceDir;
        private final String displayName;
        private final PipedInputStream pipedIn;
        private final PipedOutputStream pipedOut;
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        private final AtomicBoolean started = new AtomicBoolean(false);
        private volatile Thread producer;

        StreamingZipFileWriter(final Path sourceDir, final String displayName) throws IOException {
            this.sourceDir = sourceDir;
            this.displayName = displayName;
            this.pipedIn = new PipedInputStream(64 * 1024);
            this.pipedOut = new PipedOutputStream(this.pipedIn);
        }

        @Override
        public InputStream getInputStream() throws IOException {
            if (!started.compareAndSet(false, true)) {
                throw new IllegalStateException("getInputStream() called more than once on StreamingZipFileWriter");
            }
            producer = new Thread(this::produce, "batch-transfer-zip-producer-" + displayName);
            producer.setDaemon(true);
            producer.start();
            return pipedIn;
        }

        private void produce() {
            final ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(pipedOut, 65536));
            zos.setLevel(Deflater.NO_COMPRESSION);
            final byte[] buffer = new byte[65536];
            try {
                // Prune everything except the SCANS directory directly under the source root (RESOURCES,
                // the session XML, etc.) and follow symlinks. The walk supplies each file's attributes,
                // so there is no second stat per file.
                Files.walkFileTree(sourceDir, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                        new SimpleFileVisitor<Path>() {
                            @Override
                            public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
                                // Only descend into the SCANS directory immediately under the source root.
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
                                    // writeEntry returns false once the consumer closes the pipe → stop walking.
                                    return writeEntry(zos, file, buffer) ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
                                }
                                return FileVisitResult.CONTINUE;
                            }
                        });
            } catch (UncheckedIOException e) {
                recordError(e.getCause());   // a source DICOM file could not be read
            } catch (IOException e) {
                recordError(e);              // the source tree could not be walked
            } finally {
                finishZip(zos);
            }
        }

        /** True if any component of {@code p} matches {@code component}, ignoring case (archive folders are conventionally upper-case). */
        private static boolean hasPathComponent(final Path p, final String component) {
            for (final Path part : p) {
                if (part.toString().equalsIgnoreCase(component)) {
                    return true;
                }
            }
            return false;
        }

        private void recordError(final Throwable cause) {
            error.set(cause);
            log.warn("Streaming zip producer for {} failed", displayName, cause);
        }

        /**
         * Copy one source file into the zip, streaming through the shared fixed-size buffer.
         * Returns {@code false} — not an error — when a write to the pipe fails: that means the
         * consumer (importer) closed the read end after taking the DICOM it needed, so there is
         * nothing left to stream. A failure to <em>read</em> the source file is a genuine producer
         * error, rethrown as {@link UncheckedIOException} so it surfaces via {@link #awaitProducer(long)}.
         *
         * <p>Streaming in fixed-size chunks (which block and drain through the 64&nbsp;KB pipe)
         * keeps producer memory constant regardless of slice size — a large multi-frame instance
         * is never buffered whole.
         */
        private boolean writeEntry(final ZipOutputStream zos, final Path filePath, final byte[] buffer) {
            try (InputStream in = Files.newInputStream(filePath)) {
                try {
                    zos.putNextEntry(new ZipEntry(sourceDir.relativize(filePath).toString()));
                } catch (IOException consumerClosedPipe) {
                    return false;
                }
                while (true) {
                    final int len;
                    try {
                        len = in.read(buffer);                 // read from source
                    } catch (IOException sourceReadError) {
                        throw new UncheckedIOException(sourceReadError);
                    }
                    if (len <= 0) {
                        break;
                    }
                    try {
                        zos.write(buffer, 0, len);             // write to the pipe (sink)
                    } catch (IOException consumerClosedPipe) {
                        return false;
                    }
                }
                try {
                    zos.closeEntry();
                } catch (IOException consumerClosedPipe) {
                    return false;
                }
                return true;
            } catch (IOException sourceOpenError) {
                throw new UncheckedIOException(sourceOpenError);
            }
        }

        /**
         * Flush and close the zip, writing its central directory. The consumer never reads past
         * the last entry, so for a large session this final write can fail once the consumer has
         * closed the pipe — that is the normal end of streaming, not an error.
         */
        private void finishZip(final ZipOutputStream zos) {
            try {
                zos.close();
            } catch (IOException consumerClosedPipe) {
                log.debug("Streaming zip producer for {}: consumer closed before the zip finished (benign)", displayName);
            }
        }

        /**
         * Close the producer side of the pipe so a producer parked on
         * {@code pipedOut.write(...)} unblocks with a "Pipe closed" IOException
         * after the consumer has stopped reading. Safe to call multiple times.
         */
        void shutdown() {
            try {
                pipedOut.close();
            } catch (IOException e) {
                log.debug("Closing PipedOutputStream raised {}", e.getMessage());
            }
        }

        /**
         * Join the producer thread up to {@code timeoutMs}, then rethrow any
         * producer-captured exception. If the producer is still alive after
         * the timeout, interrupt it and log a warning (the daemon-flag keeps
         * the JVM clean either way).
         */
        void awaitProducer(final long timeoutMs) throws IOException {
            if (producer == null) {
                return;
            }
            try {
                producer.join(timeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for streaming zip producer " + displayName, e);
            }
            final boolean timedOut = producer.isAlive();
            if (timedOut) {
                log.warn("Streaming zip producer {} did not finish within {}ms; interrupting", displayName, timeoutMs);
                producer.interrupt();
            }
            // Producer-captured error takes precedence — it's the more specific cause.
            final Throwable t = error.get();
            if (t != null) {
                if (t instanceof IOException) {
                    throw (IOException) t;
                }
                if (t instanceof RuntimeException) {
                    throw (RuntimeException) t;
                }
                if (t instanceof Error) {
                    throw (Error) t;
                }
                throw new IOException("Streaming zip producer " + displayName + " failed", t);
            }
            if (timedOut) {
                throw new IOException("Streaming zip producer " + displayName
                        + " did not finish within " + timeoutMs + "ms");
            }
        }

        @Override public String getName() { return displayName; }
        @Override public String getNestedPath() { return null; }
        @Override public void write(final File f) { /* unused — DicomZipImporter only calls getName / getInputStream */ }
        @Override public void delete() { /* unused */ }
        @Override public UPLOAD_TYPE getType() { return null; }

        @Override public void close() { shutdown(); }
    }

    private void getOrCopyExperimentOrAssessor(XnatExperimentdata sourceExperiment, XnatExperimentdata existingExperiment, XnatProjectdata destinationProjectData, UserI user, EventInfo eventInfo) throws Exception {
        if (sourceExperiment instanceof XnatImageassessordata) {
            checkOrCopyAssessor((XnatImageassessordata) sourceExperiment, existingExperiment, destinationProjectData, user, eventInfo);
        } else {
            getOrCopyExperiment(sourceExperiment, existingExperiment, destinationProjectData, user, eventInfo);
        }
    }

    private void checkOrCopyAssessor(XnatImageassessordata sourceAssess, XnatExperimentdata existingAssessor, XnatProjectdata destinationProjectData, UserI user, EventInfo eventInfo) throws Exception {
        if (existingAssessor != null) {
            throwForLabelConflictAssessor(sourceAssess, existingAssessor, destinationProjectData, user, eventInfo);
        } else {
            copyAssessor(sourceAssess, destinationProjectData, user);
        }
    }

    private void copyAssessor(XnatImageassessordata sourceAssess, XnatProjectdata destinationProjectData, UserI user) throws Exception {
        final XnatExperimentdata sourceExperiment = sourceAssess.getImageSessionData();
        final XnatExperimentdata existingExperiment = XnatExperimentdata.GetExptByProjectIdentifier(destinationProjectData.getId(), sourceExperiment.getLabel(), null, false);
        final XnatExperimentdata destinationExperiment = getOrCopyExperiment(sourceExperiment, existingExperiment, destinationProjectData, user, null);
        final String sourceProject = sourceAssess.getProject();
        final String filepath = sourceExperiment.getArchiveRootPath() + "arc001/" + sourceExperiment.getArchiveDirectoryName() + "/ASSESSORS/" + sourceAssess.getArchiveDirectoryName();
        final String newFilepath = filepath.replace("/" + sourceProject + "/", "/" + destinationProjectData.getId() + "/");

        // Remove shares prior to copy (not strictly necessary to do this before rather than after copying, but why
        // copy something only to remove it?)
        int nshare = sourceAssess.getSharing_share().size();
        for (int i = nshare - 1; i >= 0; i--) {
            sourceAssess.removeSharing_share(i);
        }

        final ItemI copy = sourceAssess.getItem().copy();
        final String newId = XnatExperimentdata.CreateNewID();
        final XnatImageassessordata newAssessor = new XnatImageassessordata(copy);


        setOriginalProjectField(newAssessor, getOriginalProject(sourceAssess));
        newAssessor.setProject(destinationProjectData.getId());
        newAssessor.setImagesessionId(destinationExperiment.getId());
        newAssessor.setId(newId);

        for (final XnatAbstractresourceI res : newAssessor.getResources_resource()) {
            fixPaths(res, filepath, newFilepath);
        }
        for (final XnatAbstractresourceI res : newAssessor.getIn_file()) {
            fixPaths(res, filepath, newFilepath);
        }
        for (final XnatAbstractresourceI res : newAssessor.getOut_file()) {
            fixPaths(res, filepath, newFilepath);
        }

        XnatUtils.doActionWithWorkflow(user, sourceAssess, sourceWorkflowLabel(TransferMode.CLONE, sourceAssess.getLabel(), destinationProjectData.getId()), () -> {
            saveItemAndCopyFiles(user, sourceAssess, newAssessor, filepath, newFilepath);
            return true;
        });
    }

    private XnatExperimentdata getOrCopyExperiment(XnatExperimentdata sourceExperiment, XnatExperimentdata existingExperiment, XnatProjectdata destinationProjectData, UserI user, EventInfo eventInfo) throws Exception {
        XnatExperimentdata destinationExperiment;
        if (existingExperiment != null) {
            throwForLabelConflictExperiment(sourceExperiment, existingExperiment, destinationProjectData, user, eventInfo);
            destinationExperiment = existingExperiment;
        } else {
            destinationExperiment = copyExperiment(sourceExperiment, destinationProjectData, user);
        }
        return destinationExperiment;
    }

    private XnatExperimentdata copyExperiment(XnatExperimentdata sourceExpt, XnatProjectdata destinationProjectData, UserI user) throws Exception {
        final String sourceProject = sourceExpt.getProject();
        final String filepath = sourceExpt.getArchiveRootPath() + "arc001/" + sourceExpt.getArchiveDirectoryName();
        final String newFilepath = filepath.replace("/" + sourceProject + "/", "/" + destinationProjectData.getId() + "/");
        final String newId = XnatExperimentdata.CreateNewID();

        // Note: we need to remove children from source prior to making a copy to avoid permissions issues that arise if
        // children haven't been shared. We never save the modified source object, we just copy it and save the copy.
        if (sourceExpt instanceof XnatImagesessiondata) {
            // Remove child assessors, which will be copied separately
            List<XnatImageassessordata> assessors = ((XnatImagesessiondata) sourceExpt).getAssessors_assessor();
            if (assessors != null) {
                for (int i = assessors.size() - 1; i >= 0; --i) {
                    ((XnatImagesessiondata) sourceExpt).removeAssessors_assessor(i);
                }
            }
        }

        // Remove shares
        int nshare = sourceExpt.getSharing_share().size();
        for (int i = nshare - 1; i >= 0; i--) {
            sourceExpt.removeSharing_share(i);
        }

        final ItemI copy = sourceExpt.getItem().copy();
        final XnatExperimentdata newExperiment = sourceExpt instanceof XnatImagesessiondata ? new XnatImagesessiondata(copy) : new XnatExperimentdata(copy);
        final XnatSubjectdata sourceSubject = XnatUtils.getSubject((String) newExperiment.getProperty("subject_ID"), user);
        final XnatSubjectdata existingSubject = XnatSubjectdata.GetSubjectByProjectIdentifier(destinationProjectData.getId(), sourceSubject.getLabel(), null, false);
        final XnatSubjectdata destinationSubject = getOrCopySubject(sourceSubject, existingSubject, destinationProjectData, user, null);

        newExperiment.setProperty("subject_ID", destinationSubject.getId());
        newExperiment.setProject(destinationProjectData.getId());
        newExperiment.setId(newId);
        setOriginalProjectField(newExperiment, getOriginalProject(sourceExpt));

        if (newExperiment instanceof XnatImagesessiondata) {
            // Update scans and recons
            for (final XnatImagescandataI scan : ((XnatImagesessiondata) newExperiment).getScans_scan()) {
                scan.setImageSessionId(newId);
                for (final XnatAbstractresourceI res : scan.getFile()) {
                    fixPaths(res, filepath, newFilepath);
                }
            }

            for (final XnatReconstructedimagedataI recon :
                    ((XnatImagesessiondata) newExperiment).getReconstructions_reconstructedimage()) {
                recon.setImageSessionId(newId);
                for (final XnatAbstractresourceI res : recon.getIn_file()) {
                    fixPaths(res, filepath, newFilepath);
                }
                for (final XnatAbstractresourceI res : recon.getOut_file()) {
                    fixPaths(res, filepath, newFilepath);
                }
            }
        }

        // Update resource paths
        for (final XnatAbstractresourceI res : newExperiment.getResources_resource()) {
            fixPaths(res, filepath, newFilepath);
        }

        XnatUtils.doActionWithWorkflow(user, sourceExpt, sourceWorkflowLabel(TransferMode.CLONE, sourceExpt.getLabel(), destinationProjectData.getId()), () -> {
            saveItemAndCopyFiles(user, sourceExpt, newExperiment, filepath, newFilepath);
            return true;
        });
        return newExperiment;
    }

    private XnatSubjectdata getOrCopySubject(XnatSubjectdata sourceSubject, XnatSubjectdata existingSubject, XnatProjectdata destinationProjectData, UserI user, EventInfo eventInfo) throws Exception {
        XnatSubjectdata destinationSubject;
        if (existingSubject != null) {
            throwForLabelConflictSubject(sourceSubject, existingSubject, destinationProjectData, user, eventInfo);
            destinationSubject = existingSubject;
        } else {
            destinationSubject = copySubject(sourceSubject, destinationProjectData, user);
        }
        return destinationSubject;
    }

    private XnatSubjectdata copySubject(XnatSubjectdata sourceSubject, XnatProjectdata destinationProjectData, UserI user) throws Exception {
        final String filepath = sourceSubject.getArchiveRootPath() + "subjects/" + sourceSubject.getArchiveDirectoryName();
        final String newFilepath = filepath.replace("/" + sourceSubject.getProject() + "/", "/" + destinationProjectData.getId() + "/");

        // Note: we need to remove children from source prior to making a copy to avoid permissions issues that arise if
        // children haven't been shared. We never save the modified source object, we just copy it and save the copy.
        List<XnatSubjectassessordataI> experiments = sourceSubject.getExperiments_experiment();
        if (experiments != null) {
            for (int i = experiments.size() - 1; i >= 0; --i) {
                sourceSubject.removeExperiments_experiment(i);
            }
        }

        // Remove shares
        int nshare = sourceSubject.getSharing_share().size();
        for (int i = nshare - 1; i >= 0; i--) {
            sourceSubject.removeSharing_share(i);
        }

        ItemI copy = sourceSubject.getItem().copy();
        XnatSubjectdata newSubject = new XnatSubjectdata(copy);
        newSubject.setId(XnatSubjectdata.CreateNewID());
        newSubject.setLabel(sourceSubject.getLabel());
        newSubject.setProject(destinationProjectData.getId());
        setOriginalProjectField(newSubject, getOriginalProject(sourceSubject));

        // Update resource paths
        for (final XnatAbstractresourceI res : newSubject.getResources_resource()) {
            fixPaths(res, filepath, newFilepath);
        }

        XnatUtils.doActionWithWorkflow(user, sourceSubject, sourceWorkflowLabel(TransferMode.CLONE, sourceSubject.getLabel(), destinationProjectData.getId()), () -> {
            saveItemAndCopyFiles(user, sourceSubject, newSubject, filepath, newFilepath);
            return true;
        });
        return newSubject;
    }

    /**
     * Builds the source-item workflow label for Clone/Reimport, e.g. "Cloned sess1 to project P2".
     * Centralized so the wording stays consistent across operations. (Share keeps its own label.)
     */
    private static String sourceWorkflowLabel(final TransferMode mode, final String sourceLabel, final String destProjectId) {
        return mode.getPastAction() + " " + sourceLabel + " to project " + destProjectId;
    }

    /**
     * Saves the new item, links the source item's files into the new item's archive directory, and will run the destination
     * project's anonymization script if the item is an image session.
     *
     * @param user       - The user doing the action.
     * @param sourceItem - The source item the new item was copied from.
     * @param newItem    - The new item we are saving
     * @param source     - The source filepath
     * @param dest       - The destination filepath
     * @throws Exception
     */
    // Package-private (not private) so BatchTransferServiceImplTest can drive the save+link path directly.
    void saveItemAndCopyFiles(final UserI user, final ArchivableItem sourceItem, final ArchivableItem newItem, final String source, final String dest) throws Exception {
        try {
            EventDetails details = EventUtils.newEventInstance(EventUtils.CATEGORY.DATA, EventUtils.TYPE.WEB_SERVICE, "Item Saved");
            SaveItemHelper.authorizedSave(newItem, user, false, false, details);
        } catch (Exception e) {
            throw new Exception("Failed to save item", e);
        }

        // Link files directly (no separate workflow): the enclosing source-item workflow
        // (e.g. "Cloned sess1 to project P2") already wraps this whole method, so a link failure
        // still fails that workflow and triggers the rollback below. The destination copy
        // intentionally carries no plugin workflow of its own.
        try {
            FileUtil.linkFiles(source, dest);
        } catch (Exception e) {
            XnatUtils.deleteItemWithoutSecurity(newItem);
            final Path destDir = Paths.get(dest);
            final Path parentDir = destDir.getParent();
            final File parentFile = parentDir.toFile();
            FileUtil.deleteDirectoryWithoutException(destDir.toFile());
            if (Files.isDirectory(parentDir)) {
                if (!FileUtils.HasFiles(parentFile)) {
                    FileUtil.deleteDirectoryWithoutException(parentFile);
                }
            }
            throw e;
        }
    }
    
    /**
     * Throw an exception if the existing assessor or experiment is NOT the assessor we would be creating by proceeding with the share/copy
     *
     * @param sourceAssess           the source
     * @param existingAssess         the existing expt
     * @param destinationProjectData the destination project
     * @param user                   the user
     * @param eventInfo              event info for tracking
     * @throws Exception when existing expt with this label came from a different project or is not an assessor
     */
    private void throwForLabelConflictAssessor(XnatImageassessordata sourceAssess, @Nonnull XnatExperimentdata existingAssess, XnatProjectdata destinationProjectData, UserI user, EventInfo eventInfo) throws Exception {
        if (!(existingAssess instanceof XnatImageassessordata)) {
            // If the project already has an experiment with this label (non image assessor). We are unable to share due to conflict.
            final String msg = String.format("An experiment with the same label (%s) already exists in project %s.", sourceAssess.getLabel(), destinationProjectData.getId());
            throw new Exception(msg);
        }
        throwForLabelConflictExperiment(sourceAssess, existingAssess, destinationProjectData, user, eventInfo);
    }

    /**
     * Throw an exception if the existing expt is NOT the expt we would be creating by proceeding with the share/copy
     *
     * @param sourceExperiment       the source
     * @param existingExperiment     the existing expt
     * @param destinationProjectData the destination project
     * @param user                   the user
     * @param eventInfo              event info for tracking
     * @throws Exception when existing expt with this label came from a different project
     */
    private void throwForLabelConflictExperiment(XnatExperimentdata sourceExperiment, @Nonnull XnatExperimentdata existingExperiment, XnatProjectdata destinationProjectData, UserI user, EventInfo eventInfo) throws Exception {
        // If the experiment already exists, try to figure out where it came from.
        if (getOriginalProject(sourceExperiment).equals(getOriginalProject(existingExperiment))) {
            // If the primary project matches the source project, we know this experiment was previously shared using this copy method.
            if (eventInfo != null) {
                final String msg = String.format("%s has already been cloned into the destination project %s. Skipping ... ", existingExperiment.getLabel(), destinationProjectData.getId());
                eventService.triggerEvent(BatchTransferEvent.progress(user.getID(), eventInfo.getProgress(), eventInfo.getTrackingId(), msg));
            }
        } else {
            // If the primary project field doesn't match, this experiment was either shared from a different project, or this project
            // contains an experiment with the same label.  So we are unable to copy the experiment due to a conflict.
            final String msg = String.format("An experiment with the same label (%s) already exists in project %s.",
                    sourceExperiment.getLabel(), destinationProjectData.getId());
            log.debug(msg);
            throw new Exception(msg);
        }
    }

    /**
     * Throw an exception if the existing subject is NOT the subject we would be creating by proceeding with the share/copy
     *
     * @param sourceSubject          the source
     * @param existingSubject        the existing subject
     * @param destinationProjectData the destination project
     * @param user                   the user
     * @param eventInfo              event info for tracking
     * @throws Exception when existing subject with this label came from a different project
     */
    private void throwForLabelConflictSubject(XnatSubjectdata sourceSubject, @Nonnull XnatSubjectdata existingSubject, XnatProjectdata destinationProjectData, UserI user, EventInfo eventInfo) throws Exception {
        // If the subject already exists, try to figure out where it came from.
        if (getOriginalProject(sourceSubject).equals(getOriginalProject(existingSubject))) {
            // If the original project matches the source project, we know this subject was previously shared using this copy method.
            if (eventInfo != null) {
                final String msg = String.format("%s has already been cloned into the destination project %s. Skipping ... ", existingSubject.getLabel(), destinationProjectData.getId());
                eventService.triggerEvent(BatchTransferEvent.progress(user.getID(), eventInfo.getProgress(), eventInfo.getTrackingId(), msg));
            }
        } else {
            // If the primary project field doesn't match, this subject was either shared from a different project, or this project
            // contains a subject with the same label.  So we are unable to copy the subject due to a conflict.
            final String msg = String.format("A subject with the same label (%s) already exists in project %s.",
                    sourceSubject.getLabel(), destinationProjectData.getId());
            log.debug(msg);
            throw new Exception(msg);
        }
    }

    private void validateRequest(TransferRequest request) throws Exception {
        if (StringUtils.isBlank(request.getId())) {
            throw new Exception("Id must not be empty.");
        }

        if (StringUtils.isBlank(request.getDestinationProject())) {
            throw new Exception("Destination project cannot be empty.");
        }

        if (null == request.getMode()) {
            throw new Exception("Mode must not be null.");
        }
    }

    private void setOriginalProjectField(ArchivableItem item, String primaryProject) throws Exception {
        try {
            if (item instanceof XnatExperimentdata) {
                if (null == ((XnatExperimentdata) item).getFieldByName(Fields.ORIGINAL_PROJECT)) {
                    XnatExperimentdataField field = new XnatExperimentdataField();
                    field.setName(Fields.ORIGINAL_PROJECT);
                    field.setField(primaryProject);
                    ((XnatExperimentdata) item).setFields_field(field);
                }
            } else if (item instanceof XnatSubjectdata) {
                if (null == ((XnatSubjectdata) item).getFieldByName(Fields.ORIGINAL_PROJECT)) {
                    XnatSubjectdataField field = new XnatSubjectdataField();
                    field.setName(Fields.ORIGINAL_PROJECT);
                    field.setField(primaryProject);
                    ((XnatSubjectdata) item).setFields_field(field);
                }
            } else {
                final String msg = String.format("I don't know how to set the primary project field for item: %s", item.getId());
                log.error(msg);
                throw new Exception(msg);
            }
        } catch (Exception e) {
            final String msg = String.format("Failed to set primary project for new item: %s", item.getId());
            log.debug(msg, e);
            throw new Exception(msg, e);
        }
    }

    private String getOriginalProject(ArchivableItem item) throws Exception {
        String originalProject;
        if (item instanceof XnatExperimentdata) {
            originalProject = (String) ((XnatExperimentdata) item).getFieldByName(Fields.ORIGINAL_PROJECT);
        } else if (item instanceof XnatSubjectdata) {
            originalProject = (String) ((XnatSubjectdata) item).getFieldByName(Fields.ORIGINAL_PROJECT);
        } else {
            final String msg = String.format("I don't know how to get the original project field for item: %s", item.getId());
            log.error(msg);
            throw new Exception(msg);
        }
        return StringUtils.defaultIfBlank(originalProject, item.getProject());
    }

    private void fixPaths(XnatAbstractresourceI resource, String filepath, String newFilepath) throws Exception {
        if (!Files.exists(Paths.get(filepath))) {
            throw new Exception("Unable to locate files for abstract resource " + resource.getXnatAbstractresourceId() +
                    ", parent likely stored in non-standard location.");
        }
        if (resource instanceof XnatResource) {
            String path = ((XnatResource) resource).getUri();
            String newURI = path.replace(filepath, newFilepath);
            ((XnatResource) resource).setUri(newURI);
        } else if (resource instanceof XnatResourceseries) {
            String path = ((XnatResourceseries) resource).getPath();
            String newURI = path.replace(filepath, newFilepath);
            ((XnatResourceseries) resource).setPath(newURI);
        }
    }
}
