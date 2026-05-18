package org.nrg.xnatx.plugins.transfer.service.impl;

import org.nrg.xnatx.plugins.transfer.config.BatchTransferServiceConfig;
import org.nrg.xnatx.plugins.transfer.event.BatchTransferEvent;
import org.nrg.xnatx.plugins.transfer.model.BatchTransfer;
import org.nrg.xnatx.plugins.transfer.model.TransferMode;
import org.nrg.xnatx.plugins.transfer.model.TransferRequest;
import org.nrg.xnatx.plugins.transfer.util.XnatUtils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.nrg.framework.services.NrgEventService;
import org.nrg.xdat.om.XnatImageassessordata;
import org.nrg.xdat.om.XnatImagesessiondata;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xdat.om.XnatSubjectdata;
import org.nrg.xdat.om.base.BaseXnatExperimentdata;
import org.nrg.xdat.om.base.BaseXnatSubjectdata;
import org.nrg.xdat.security.helpers.Features;
import org.nrg.xdat.security.helpers.Permissions;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.helpers.prearchive.PrearcSession;
import org.nrg.xnat.helpers.prearchive.PrearcUtils;
import org.nrg.xnat.helpers.uri.URIManager;
import org.nrg.xnat.restlet.util.FileWriterWrapperI;
import org.nrg.xnat.services.messaging.prearchive.PrearchiveOperationRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the IMPORT operation of {@link BatchTransferServiceImpl}.
 *
 * <p>The service bean is a Mockito spy (see BatchTransferServiceConfig) so tests
 * can stub the protected {@code runImporter} seam without loading
 * {@code DicomZipImporter} — whose supertype static initialisers require
 * XDAT / Spring wiring that isn't present in a unit-test JVM.
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = BatchTransferServiceConfig.class)
public class BatchTransferServiceImplTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Autowired private BatchTransferServiceImpl service;
    @Autowired private NrgEventService nrgEventService;
    @Autowired private ExecutorService executorService;
    @Autowired private UserI user;
    @Autowired private XnatProjectdata destinationProjectData;
    @Autowired private XnatImagesessiondata imageSession;
    @Autowired private XnatImageassessordata imageAssessor;
    @Autowired private XnatSubjectdata subject;

    private static final String DEST_PROJECT = "destProj";
    private static final String SRC_PROJECT  = "srcProj";
    private static final String EXP_ID       = "exp1";
    private static final String LABEL        = "sess1";
    private static final String TRACKING_ID  = "t1";

    @Before
    public void setUp() throws Exception {
        Mockito.reset(service, nrgEventService, executorService, user,
                destinationProjectData, imageSession, imageAssessor, subject);

        when(user.getID()).thenReturn(42);

        // Run submitted Runnables on the test thread so per-thread MockedStatic
        // scopes opened in a test apply to the batch loop.
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(executorService).submit(any(Runnable.class));

        when(destinationProjectData.getId()).thenReturn(DEST_PROJECT);
        when(destinationProjectData.getCachePath())
                .thenReturn(tmp.newFolder("cache").getAbsolutePath());
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private BatchTransfer importRequest(String id) {
        return new BatchTransfer(
                Collections.singletonList(
                        new TransferRequest(DEST_PROJECT, id, TransferMode.REIMPORT)),
                TRACKING_ID);
    }

    private List<BatchTransferEvent> captureEvents() {
        ArgumentCaptor<BatchTransferEvent> cap = ArgumentCaptor.forClass(BatchTransferEvent.class);
        verify(nrgEventService, atLeastOnce()).triggerEvent(cap.capture());
        return cap.getAllValues();
    }

    private Map<String, Object> uriProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(URIManager.PROJECT_ID, DEST_PROJECT);
        props.put(PrearcUtils.PREARC_TIMESTAMP, "20260101_000000");
        props.put(PrearcUtils.PREARC_SESSION_FOLDER, LABEL);
        return props;
    }

    private void stubImageSessionAsSource() throws Exception {
        when(imageSession.getXSIType()).thenReturn("xnat:mrSessionData");
        when(imageSession.getProject()).thenReturn(SRC_PROJECT);
        when(imageSession.getId()).thenReturn(EXP_ID);
        when(imageSession.getLabel()).thenReturn(LABEL);
        when(imageSession.getCurrentSessionFolder(true))
                .thenReturn(tmp.newFolder("src").getAbsolutePath());
    }

    // -----------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------

    /**
     * Null or empty request list: never submit a task and never emit events.
     */
    @Test
    public void submitTransferRequest_emptyOrNullRequests_isNoop() {
        service.submitTransferRequest(new BatchTransfer(null, "t-null"), user);
        service.submitTransferRequest(new BatchTransfer(Collections.emptyList(), "t-empty"), user);

        verify(executorService, never()).submit(any(Runnable.class));
        verify(nrgEventService, never()).triggerEvent(any(BatchTransferEvent.class));
    }

    /**
     * IMPORT on an XnatImageassessordata throws at the top of importExperiment
     * (BatchTransferServiceImpl.java:191-193). The failure is caught by batchTransfer
     * and results in a Failed event plus a terminal Warning event (line 168).
     */
    @Test
    public void importAssessor_throwsAndFailsBatch() {
        when(imageAssessor.getXSIType()).thenReturn("xnat:imageAssessorData");
        when(imageAssessor.getProject()).thenReturn(SRC_PROJECT);
        when(imageAssessor.getId()).thenReturn(EXP_ID);
        when(imageAssessor.getLabel()).thenReturn(LABEL);

        try (MockedStatic<XnatUtils>   utils = mockStatic(XnatUtils.class);
             MockedStatic<Permissions> perms = mockStatic(Permissions.class);
             MockedStatic<Features>    feats = mockStatic(Features.class)) {

            utils.when(() -> XnatUtils.getProject(DEST_PROJECT, user))
                    .thenReturn(destinationProjectData);
            utils.when(() -> XnatUtils.getArchivableItem(EXP_ID, null))
                    .thenReturn(imageAssessor);

            perms.when(() -> Permissions.canRead(user, imageAssessor)).thenReturn(true);
            perms.when(() -> Permissions.canCreate(eq(user), anyString(), eq(DEST_PROJECT)))
                    .thenReturn(true);
            feats.when(() -> Features.checkRestrictedFeature(
                    eq(user), anyString(), anyString())).thenReturn(true);

            service.submitTransferRequest(importRequest(EXP_ID), user);
        }

        List<BatchTransferEvent> events = captureEvents();
        BatchTransferEvent failed = events.stream()
                .filter(e -> e.getStatus() == BatchTransferEvent.Status.Failed)
                .findFirst().orElse(null);
        assertNotNull("expected a Failed event", failed);
        assertTrue("failure message should mention assessor rejection: " + failed.getMessage(),
                failed.getMessage() != null
                        && failed.getMessage().contains("not supported for assessors"));
        assertEquals(BatchTransferEvent.Status.Warning, events.get(events.size() - 1).getStatus());
    }

    /**
     * IMPORT on an XnatSubjectdata hits the subject branch at
     * BatchTransferServiceImpl.java:137-138, which just logs a warning and does not
     * throw. The batch completes normally with no Failed event.
     */
    @Test
    public void importSubject_logsAndContinues() throws Exception {
        when(subject.getXSIType()).thenReturn("xnat:subjectData");
        when(subject.getProject()).thenReturn(SRC_PROJECT);
        when(subject.getId()).thenReturn(EXP_ID);
        when(subject.getLabel()).thenReturn(LABEL);

        try (MockedStatic<XnatUtils>       utils    = mockStatic(XnatUtils.class);
             MockedStatic<Permissions>     perms    = mockStatic(Permissions.class);
             MockedStatic<Features>        feats    = mockStatic(Features.class);
             MockedStatic<BaseXnatSubjectdata> subMock = mockStatic(BaseXnatSubjectdata.class)) {

            utils.when(() -> XnatUtils.getProject(DEST_PROJECT, user))
                    .thenReturn(destinationProjectData);
            utils.when(() -> XnatUtils.getArchivableItem(EXP_ID, null))
                    .thenReturn(subject);

            perms.when(() -> Permissions.canRead(user, subject)).thenReturn(true);
            perms.when(() -> Permissions.canCreate(eq(user), anyString(), eq(DEST_PROJECT)))
                    .thenReturn(true);
            feats.when(() -> Features.checkRestrictedFeature(
                    eq(user), anyString(), anyString())).thenReturn(true);
            subMock.when(() -> BaseXnatSubjectdata.GetSubjectByProjectIdentifier(
                    eq(DEST_PROJECT), eq(LABEL), any(UserI.class), eq(false))).thenReturn(null);

            service.submitTransferRequest(importRequest(EXP_ID), user);

            // Subject IMPORT must never invoke the importer seam.
            verify(service, never()).runImporter(
                    any(UserI.class), any(FileWriterWrapperI.class), any(Map.class));
        }

        List<BatchTransferEvent> events = captureEvents();
        assertTrue("subject IMPORT should not emit a Failed event",
                events.stream().noneMatch(e -> e.getStatus() == BatchTransferEvent.Status.Failed));
        assertEquals(BatchTransferEvent.Status.Completed, events.get(events.size() - 1).getStatus());
    }

    /**
     * validateRequest (BatchTransferServiceImpl.java:574-586): blank id, blank
     * destination project, and null operation each produce a Failed event
     * before XnatUtils.getProject is invoked.
     */
    @Test
    public void validateRequest_missingFields_fails() {
        List<TransferRequest> bad = new ArrayList<>();
        bad.add(new TransferRequest(DEST_PROJECT, "",     TransferMode.REIMPORT)); // blank id
        bad.add(new TransferRequest("",           EXP_ID, TransferMode.REIMPORT)); // blank destination
        bad.add(new TransferRequest(DEST_PROJECT, EXP_ID, null));                  // null operation

        for (TransferRequest req : bad) {
            Mockito.reset(nrgEventService);
            try (MockedStatic<XnatUtils> utils = mockStatic(XnatUtils.class)) {
                service.submitTransferRequest(
                        new BatchTransfer(Collections.singletonList(req), TRACKING_ID), user);
                utils.verify(() -> XnatUtils.getProject(anyString(), any(UserI.class)), never());
            }
            List<BatchTransferEvent> events = captureEvents();
            assertTrue("expected Failed event for " + req,
                    events.stream().anyMatch(e -> e.getStatus() == BatchTransferEvent.Status.Failed));
        }
    }

    /**
     * When source project equals destination project, batchTransfer emits a Failed
     * event (BatchTransferServiceImpl.java:109-111) before importExperiment runs.
     */
    @Test
    public void importSameSourceAndDest_fails() throws Exception {
        when(imageSession.getXSIType()).thenReturn("xnat:mrSessionData");
        when(imageSession.getProject()).thenReturn(DEST_PROJECT); // source == dest
        when(imageSession.getId()).thenReturn(EXP_ID);
        when(imageSession.getLabel()).thenReturn(LABEL);

        try (MockedStatic<XnatUtils>   utils = mockStatic(XnatUtils.class);
             MockedStatic<Permissions> perms = mockStatic(Permissions.class);
             MockedStatic<Features>    feats = mockStatic(Features.class)) {

            utils.when(() -> XnatUtils.getProject(DEST_PROJECT, user))
                    .thenReturn(destinationProjectData);
            utils.when(() -> XnatUtils.getArchivableItem(EXP_ID, null))
                    .thenReturn(imageSession);
            perms.when(() -> Permissions.canRead(user, imageSession)).thenReturn(true);
            perms.when(() -> Permissions.canCreate(eq(user), anyString(), eq(DEST_PROJECT)))
                    .thenReturn(true);
            feats.when(() -> Features.checkRestrictedFeature(
                    eq(user), anyString(), anyString())).thenReturn(true);

            service.submitTransferRequest(importRequest(EXP_ID), user);
        }

        List<BatchTransferEvent> events = captureEvents();
        BatchTransferEvent failed = events.stream()
                .filter(e -> e.getStatus() == BatchTransferEvent.Status.Failed)
                .findFirst().orElse(null);
        assertNotNull("expected a Failed event", failed);
        assertTrue("Failed message should mention the source/destination conflict: "
                        + failed.getMessage(),
                failed.getMessage() != null
                        && failed.getMessage().contains("same as the source project"));
    }

    /**
     * Happy path: IMPORT on an XnatImagesessiondata invokes the (spy-stubbed)
     * runImporter with a streaming wrapper and commits URIs to the prearchive.
     * Terminal event is Completed.
     */
    @Test
    public void importImagesession_happyPath() throws Exception {
        stubImageSessionAsSource();

        final List<String> importerUris = Collections.singletonList(
                "/prearchive/projects/" + DEST_PROJECT + "/20260101_000000/" + LABEL);
        doReturn(importerUris).when(service).runImporter(
                any(UserI.class), any(FileWriterWrapperI.class), any(Map.class));

        try (MockedStatic<XnatUtils>              utils    = mockStatic(XnatUtils.class);
             MockedStatic<Permissions>            perms    = mockStatic(Permissions.class);
             MockedStatic<Features>               feats    = mockStatic(Features.class);
             MockedStatic<BaseXnatExperimentdata> expt     = mockStatic(BaseXnatExperimentdata.class);
             MockedStatic<PrearcUtils>            prearc   = mockStatic(PrearcUtils.class);
             // Bypass PrearcSession / PrearchiveOperationRequest real constructors —
             // they depend on XDAT/prearchive state not available in unit tests.
             MockedConstruction<PrearcSession> sess = mockConstruction(PrearcSession.class);
             MockedConstruction<PrearchiveOperationRequest> req =
                     mockConstruction(PrearchiveOperationRequest.class)) {

            utils.when(() -> XnatUtils.getProject(DEST_PROJECT, user))
                    .thenReturn(destinationProjectData);
            utils.when(() -> XnatUtils.getArchivableItem(EXP_ID, null))
                    .thenReturn(imageSession);
            utils.when(() -> XnatUtils.doActionWithWorkflow(
                    any(UserI.class), any(), anyString(), any(Callable.class)))
                    .thenAnswer(inv -> {
                        ((Callable<?>) inv.getArgument(3)).call();
                        return true;
                    });

            perms.when(() -> Permissions.canRead(user, imageSession)).thenReturn(true);
            perms.when(() -> Permissions.canCreate(eq(user), anyString(), eq(DEST_PROJECT)))
                    .thenReturn(true);
            feats.when(() -> Features.checkRestrictedFeature(
                    eq(user), anyString(), anyString())).thenReturn(true);
            expt.when(() -> BaseXnatExperimentdata.GetExptByProjectIdentifier(
                    eq(DEST_PROJECT), eq(LABEL), any(UserI.class), eq(false)))
                    .thenReturn(null);

            prearc.when(() -> PrearcUtils.parseURI(anyString())).thenReturn(uriProps());

            service.submitTransferRequest(importRequest(EXP_ID), user);

            verify(service).runImporter(eq(user), any(FileWriterWrapperI.class), any(Map.class));
            prearc.verify(() -> PrearcUtils.parseURI(importerUris.get(0)));
            prearc.verify(() -> PrearcUtils.queuePrearchiveOperation(any()));
            assertEquals(1, sess.constructed().size());
            assertEquals(1, req.constructed().size());
        }

        List<BatchTransferEvent> events = captureEvents();
        assertEquals(BatchTransferEvent.Status.Waiting, events.get(0).getStatus());
        assertEquals(BatchTransferEvent.Status.Completed, events.get(events.size() - 1).getStatus());
        assertTrue(events.stream().anyMatch(e -> e.getStatus() == BatchTransferEvent.Status.InProgress));
        assertFalse(events.stream().anyMatch(e -> e.getStatus() == BatchTransferEvent.Status.Failed));
    }

    /**
     * If runImporter returns an empty URI list (source experiment had no
     * DICOM files), importExperiment throws inside the workflow callable so
     * the batch emits a Failed event instead of silently completing.
     */
    @Test
    public void importerReturnsEmpty_failsBatch() throws Exception {
        stubImageSessionAsSource();

        doReturn(Collections.emptyList()).when(service).runImporter(
                any(UserI.class), any(FileWriterWrapperI.class), any(Map.class));

        try (MockedStatic<XnatUtils>              utils    = mockStatic(XnatUtils.class);
             MockedStatic<Permissions>            perms    = mockStatic(Permissions.class);
             MockedStatic<Features>               feats    = mockStatic(Features.class);
             MockedStatic<BaseXnatExperimentdata> expt     = mockStatic(BaseXnatExperimentdata.class)) {

            utils.when(() -> XnatUtils.getProject(DEST_PROJECT, user))
                    .thenReturn(destinationProjectData);
            utils.when(() -> XnatUtils.getArchivableItem(EXP_ID, null))
                    .thenReturn(imageSession);
            utils.when(() -> XnatUtils.doActionWithWorkflow(
                    any(UserI.class), any(), anyString(), any(Callable.class)))
                    .thenAnswer(inv -> {
                        ((Callable<?>) inv.getArgument(3)).call();
                        return true;
                    });
            perms.when(() -> Permissions.canRead(user, imageSession)).thenReturn(true);
            perms.when(() -> Permissions.canCreate(eq(user), anyString(), eq(DEST_PROJECT)))
                    .thenReturn(true);
            feats.when(() -> Features.checkRestrictedFeature(
                    eq(user), anyString(), anyString())).thenReturn(true);
            expt.when(() -> BaseXnatExperimentdata.GetExptByProjectIdentifier(
                    eq(DEST_PROJECT), eq(LABEL), any(UserI.class), eq(false)))
                    .thenReturn(null);

            service.submitTransferRequest(importRequest(EXP_ID), user);
        }

        List<BatchTransferEvent> events = captureEvents();
        BatchTransferEvent failed = events.stream()
                .filter(e -> e.getStatus() == BatchTransferEvent.Status.Failed)
                .findFirst().orElse(null);
        assertNotNull("expected a Failed event when importer returns empty URI list", failed);
        assertTrue("Failed message should mention no DICOM files: " + failed.getMessage(),
                failed.getMessage() != null && failed.getMessage().contains("No DICOM files"));
    }

    /**
     * If runImporter throws, batchTransfer's per-request try/catch catches it
     * and emits a Failed event. (The workflow wrap in importExperiment
     * rewraps the cause as "<workflow name> Failed".)
     */
    @Test
    public void importerThrows_emitsFailedEvent() throws Exception {
        stubImageSessionAsSource();

        doThrow(new RuntimeException("boom")).when(service).runImporter(
                any(UserI.class), any(FileWriterWrapperI.class), any(Map.class));

        try (MockedStatic<XnatUtils>              utils    = mockStatic(XnatUtils.class);
             MockedStatic<Permissions>            perms    = mockStatic(Permissions.class);
             MockedStatic<Features>               feats    = mockStatic(Features.class);
             MockedStatic<BaseXnatExperimentdata> expt     = mockStatic(BaseXnatExperimentdata.class)) {

            utils.when(() -> XnatUtils.getProject(DEST_PROJECT, user))
                    .thenReturn(destinationProjectData);
            utils.when(() -> XnatUtils.getArchivableItem(EXP_ID, null))
                    .thenReturn(imageSession);
            utils.when(() -> XnatUtils.doActionWithWorkflow(
                    any(UserI.class), any(), anyString(), any(Callable.class)))
                    .thenAnswer(inv -> {
                        ((Callable<?>) inv.getArgument(3)).call();
                        return true;
                    });
            perms.when(() -> Permissions.canRead(user, imageSession)).thenReturn(true);
            perms.when(() -> Permissions.canCreate(eq(user), anyString(), eq(DEST_PROJECT)))
                    .thenReturn(true);
            feats.when(() -> Features.checkRestrictedFeature(
                    eq(user), anyString(), anyString())).thenReturn(true);
            expt.when(() -> BaseXnatExperimentdata.GetExptByProjectIdentifier(
                    eq(DEST_PROJECT), eq(LABEL), any(UserI.class), eq(false)))
                    .thenReturn(null);

            service.submitTransferRequest(importRequest(EXP_ID), user);
        }

        List<BatchTransferEvent> events = captureEvents();
        assertTrue("expected a Failed event when runImporter throws",
                events.stream().anyMatch(e -> e.getStatus() == BatchTransferEvent.Status.Failed));
    }
}
