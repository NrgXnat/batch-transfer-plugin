package org.nrg.xnatx.plugins.transfer.service.impl;

import org.nrg.xnatx.plugins.transfer.config.BatchTransferServiceConfig;
import org.nrg.xnatx.plugins.transfer.event.BatchTransferEvent;
import org.nrg.xnatx.plugins.transfer.model.BatchTransfer;
import org.nrg.xnatx.plugins.transfer.model.EventInfo;
import org.nrg.xnatx.plugins.transfer.model.TransferMode;
import org.nrg.xnatx.plugins.transfer.model.TransferRequest;
import org.nrg.xnatx.plugins.transfer.util.FileUtil;
import org.nrg.xnatx.plugins.transfer.util.XnatUtils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.nrg.framework.services.NrgEventService;
import org.nrg.xdat.om.XnatImageassessordata;
import org.nrg.xdat.om.XnatImagesessiondata;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xdat.om.XnatSubjectdata;
import org.nrg.xdat.om.base.BaseXnatSubjectdata;
import org.nrg.xdat.security.helpers.Features;
import org.nrg.xdat.security.helpers.Permissions;
import org.nrg.xft.event.EventUtils;
import org.nrg.xft.security.UserI;
import org.nrg.xft.utils.SaveItemHelper;
import org.nrg.xnat.helpers.prearchive.PrearcSession;
import org.nrg.xnat.turbine.utils.ArchivableItem;
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the per-item REIMPORT logic of {@link BatchTransferServiceImpl}.
 *
 * <p>Since the Reimport/Clone batch paths now fan out to JMS (a consumer calls {@code processItem}
 * per item), these tests exercise {@link BatchTransferServiceImpl#processItem} directly — the shared
 * per-item engine — rather than driving a batch through {@code submitTransferRequest}. {@code processItem}
 * does the validate/load/permission/dispatch work and <b>throws</b> on failure; it does not emit
 * activity-monitor events (the orchestrators do), so the assertions are on what it throws/returns and
 * on the {@code runImporter}/prearchive side effects.
 *
 * <p>The service bean is a Mockito spy (see {@code BatchTransferServiceConfig}) so tests can stub the
 * protected {@code runImporter} seam without loading {@code DicomZipImporter}.
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

        // Run submitted Runnables on the test thread (used by the empty/null submit test).
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

    private TransferRequest reimport(final String id) {
        return new TransferRequest(DEST_PROJECT, id, TransferMode.REIMPORT);
    }

    private EventInfo eventInfo() {
        return new EventInfo(TRACKING_ID, 0);
    }

    /** Calls processItem and returns the thrown exception, or null if it returned normally. */
    private Exception processItemCatching(final TransferRequest request) {
        try {
            service.processItem(request, user, eventInfo());
            return null;
        } catch (Exception e) {
            return e;
        }
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
     * Null or empty request list: submitTransferRequest never submits a task and never emits events.
     */
    @Test
    public void submitTransferRequest_emptyOrNullRequests_isNoop() {
        service.submitTransferRequest(new BatchTransfer(null, "t-null"), user);
        service.submitTransferRequest(new BatchTransfer(Collections.emptyList(), "t-empty"), user);

        verify(executorService, never()).submit(any(Runnable.class));
        verify(nrgEventService, never()).triggerEvent(any(BatchTransferEvent.class));
    }

    /**
     * REIMPORT of an XnatImageassessordata throws at the top of importExperiment
     * ("Reimport operation is not supported for assessors.").
     */
    @Test
    public void importAssessor_throws() {
        when(imageAssessor.getXSIType()).thenReturn("xnat:imageAssessorData");
        when(imageAssessor.getProject()).thenReturn(SRC_PROJECT);
        when(imageAssessor.getId()).thenReturn(EXP_ID);
        when(imageAssessor.getLabel()).thenReturn(LABEL);

        try (MockedStatic<XnatUtils>   utils = mockStatic(XnatUtils.class);
             MockedStatic<Permissions> perms = mockStatic(Permissions.class);
             MockedStatic<Features>    feats = mockStatic(Features.class)) {

            utils.when(() -> XnatUtils.getProject(DEST_PROJECT, user)).thenReturn(destinationProjectData);
            utils.when(() -> XnatUtils.getArchivableItem(EXP_ID, null)).thenReturn(imageAssessor);
            perms.when(() -> Permissions.canRead(user, imageAssessor)).thenReturn(true);
            perms.when(() -> Permissions.canCreate(eq(user), anyString(), eq(DEST_PROJECT))).thenReturn(true);
            feats.when(() -> Features.checkRestrictedFeature(eq(user), anyString(), anyString())).thenReturn(true);

            final Exception thrown = processItemCatching(reimport(EXP_ID));
            assertNotNull("expected processItem to throw for an assessor reimport", thrown);
            assertTrue("message should mention assessor rejection: " + thrown.getMessage(),
                    thrown.getMessage() != null && thrown.getMessage().contains("not supported for assessors"));
        }
    }

    /**
     * REIMPORT of an XnatSubjectdata is a no-op (logs a warning); processItem returns without
     * throwing and never invokes the importer seam.
     */
    @Test
    public void importSubject_isNoop() throws Exception {
        when(subject.getXSIType()).thenReturn("xnat:subjectData");
        when(subject.getProject()).thenReturn(SRC_PROJECT);
        when(subject.getId()).thenReturn(EXP_ID);
        when(subject.getLabel()).thenReturn(LABEL);

        try (MockedStatic<XnatUtils>           utils   = mockStatic(XnatUtils.class);
             MockedStatic<Permissions>         perms   = mockStatic(Permissions.class);
             MockedStatic<Features>            feats   = mockStatic(Features.class);
             MockedStatic<BaseXnatSubjectdata> subMock = mockStatic(BaseXnatSubjectdata.class)) {

            utils.when(() -> XnatUtils.getProject(DEST_PROJECT, user)).thenReturn(destinationProjectData);
            utils.when(() -> XnatUtils.getArchivableItem(EXP_ID, null)).thenReturn(subject);
            perms.when(() -> Permissions.canRead(user, subject)).thenReturn(true);
            perms.when(() -> Permissions.canCreate(eq(user), anyString(), eq(DEST_PROJECT))).thenReturn(true);
            feats.when(() -> Features.checkRestrictedFeature(eq(user), anyString(), anyString())).thenReturn(true);
            subMock.when(() -> BaseXnatSubjectdata.GetSubjectByProjectIdentifier(
                    eq(DEST_PROJECT), eq(LABEL), any(UserI.class), eq(false))).thenReturn(null);

            service.processItem(reimport(EXP_ID), user, eventInfo());

            verify(service, never()).runImporter(
                    any(UserI.class), any(FileWriterWrapperI.class), any(Map.class));
        }
    }

    /**
     * validateRequest: blank id, blank destination project, and null operation each make processItem
     * throw before XnatUtils.getProject is invoked.
     */
    @Test
    public void validateRequest_missingFields_throwsBeforeLoad() {
        final List<TransferRequest> bad = new ArrayList<>();
        bad.add(new TransferRequest(DEST_PROJECT, "",     TransferMode.REIMPORT)); // blank id
        bad.add(new TransferRequest("",           EXP_ID, TransferMode.REIMPORT)); // blank destination
        bad.add(new TransferRequest(DEST_PROJECT, EXP_ID, null));                  // null operation

        for (final TransferRequest req : bad) {
            try (MockedStatic<XnatUtils> utils = mockStatic(XnatUtils.class)) {
                final Exception thrown = processItemCatching(req);
                assertNotNull("expected processItem to throw for " + req, thrown);
                utils.verify(() -> XnatUtils.getProject(anyString(), any(UserI.class)), never());
            }
        }
    }

    /**
     * When source project equals destination project, processItem throws before importExperiment runs.
     */
    @Test
    public void importSameSourceAndDest_throws() {
        when(imageSession.getXSIType()).thenReturn("xnat:mrSessionData");
        when(imageSession.getProject()).thenReturn(DEST_PROJECT); // source == dest
        when(imageSession.getId()).thenReturn(EXP_ID);
        when(imageSession.getLabel()).thenReturn(LABEL);

        try (MockedStatic<XnatUtils>   utils = mockStatic(XnatUtils.class);
             MockedStatic<Permissions> perms = mockStatic(Permissions.class);
             MockedStatic<Features>    feats = mockStatic(Features.class)) {

            utils.when(() -> XnatUtils.getProject(DEST_PROJECT, user)).thenReturn(destinationProjectData);
            utils.when(() -> XnatUtils.getArchivableItem(EXP_ID, null)).thenReturn(imageSession);
            perms.when(() -> Permissions.canRead(user, imageSession)).thenReturn(true);
            perms.when(() -> Permissions.canCreate(eq(user), anyString(), eq(DEST_PROJECT))).thenReturn(true);
            feats.when(() -> Features.checkRestrictedFeature(eq(user), anyString(), anyString())).thenReturn(true);

            final Exception thrown = processItemCatching(reimport(EXP_ID));
            assertNotNull("expected processItem to throw when source == dest", thrown);
            assertTrue("message should mention the source/destination conflict: " + thrown.getMessage(),
                    thrown.getMessage() != null && thrown.getMessage().contains("same as the source project"));
        }
    }

    /**
     * Happy path: REIMPORT of an XnatImagesessiondata invokes the (spy-stubbed) runImporter with a
     * streaming wrapper and commits the returned URIs to the prearchive. processItem returns normally.
     */
    @Test
    public void importImagesession_happyPath() throws Exception {
        stubImageSessionAsSource();

        final List<String> importerUris = Collections.singletonList(
                "/prearchive/projects/" + DEST_PROJECT + "/20260101_000000/" + LABEL);
        doReturn(importerUris).when(service).runImporter(
                any(UserI.class), any(FileWriterWrapperI.class), any(Map.class));

        try (MockedStatic<XnatUtils>   utils  = mockStatic(XnatUtils.class);
             MockedStatic<Permissions> perms  = mockStatic(Permissions.class);
             MockedStatic<Features>    feats  = mockStatic(Features.class);
             MockedStatic<PrearcUtils> prearc = mockStatic(PrearcUtils.class);
             // Bypass PrearcSession / PrearchiveOperationRequest real constructors.
             MockedConstruction<PrearcSession> sess = mockConstruction(PrearcSession.class);
             MockedConstruction<PrearchiveOperationRequest> req =
                     mockConstruction(PrearchiveOperationRequest.class)) {

            utils.when(() -> XnatUtils.getProject(DEST_PROJECT, user)).thenReturn(destinationProjectData);
            utils.when(() -> XnatUtils.getArchivableItem(EXP_ID, null)).thenReturn(imageSession);
            utils.when(() -> XnatUtils.doActionWithWorkflow(
                    any(UserI.class), any(), anyString(), any(Callable.class)))
                    .thenAnswer(inv -> {
                        ((Callable<?>) inv.getArgument(3)).call();
                        return true;
                    });
            perms.when(() -> Permissions.canRead(user, imageSession)).thenReturn(true);
            perms.when(() -> Permissions.canCreate(eq(user), anyString(), eq(DEST_PROJECT))).thenReturn(true);
            feats.when(() -> Features.checkRestrictedFeature(eq(user), anyString(), anyString())).thenReturn(true);
            prearc.when(() -> PrearcUtils.parseURI(anyString())).thenReturn(uriProps());

            service.processItem(reimport(EXP_ID), user, eventInfo());

            verify(service).runImporter(eq(user), any(FileWriterWrapperI.class), any(Map.class));
            prearc.verify(() -> PrearcUtils.parseURI(importerUris.get(0)));
            prearc.verify(() -> PrearcUtils.queuePrearchiveOperation(any()));
            assertEquals(1, sess.constructed().size());
            assertEquals(1, req.constructed().size());
        }
    }

    /**
     * If runImporter returns an empty URI list, importExperiment throws inside the workflow callable
     * ("No DICOM files ... nothing to reimport"), so processItem throws.
     */
    @Test
    public void importerReturnsEmpty_throws() throws Exception {
        stubImageSessionAsSource();

        doReturn(Collections.emptyList()).when(service).runImporter(
                any(UserI.class), any(FileWriterWrapperI.class), any(Map.class));

        try (MockedStatic<XnatUtils>   utils = mockStatic(XnatUtils.class);
             MockedStatic<Permissions> perms = mockStatic(Permissions.class);
             MockedStatic<Features>    feats = mockStatic(Features.class)) {

            utils.when(() -> XnatUtils.getProject(DEST_PROJECT, user)).thenReturn(destinationProjectData);
            utils.when(() -> XnatUtils.getArchivableItem(EXP_ID, null)).thenReturn(imageSession);
            utils.when(() -> XnatUtils.doActionWithWorkflow(
                    any(UserI.class), any(), anyString(), any(Callable.class)))
                    .thenAnswer(inv -> {
                        ((Callable<?>) inv.getArgument(3)).call();
                        return true;
                    });
            perms.when(() -> Permissions.canRead(user, imageSession)).thenReturn(true);
            perms.when(() -> Permissions.canCreate(eq(user), anyString(), eq(DEST_PROJECT))).thenReturn(true);
            feats.when(() -> Features.checkRestrictedFeature(eq(user), anyString(), anyString())).thenReturn(true);

            final Exception thrown = processItemCatching(reimport(EXP_ID));
            assertNotNull("expected processItem to throw when the importer returns an empty URI list", thrown);
            assertTrue("message should mention no DICOM files: " + thrown.getMessage(),
                    thrown.getMessage() != null && thrown.getMessage().contains("No DICOM files"));
        }
    }

    /**
     * If runImporter throws, the failure propagates out of processItem (the real workflow wrap would
     * fail the workflow and rethrow; here doActionWithWorkflow is stubbed to invoke the callable).
     */
    @Test
    public void importerThrows_propagates() throws Exception {
        stubImageSessionAsSource();

        doThrow(new RuntimeException("boom")).when(service).runImporter(
                any(UserI.class), any(FileWriterWrapperI.class), any(Map.class));

        try (MockedStatic<XnatUtils>   utils = mockStatic(XnatUtils.class);
             MockedStatic<Permissions> perms = mockStatic(Permissions.class);
             MockedStatic<Features>    feats = mockStatic(Features.class)) {

            utils.when(() -> XnatUtils.getProject(DEST_PROJECT, user)).thenReturn(destinationProjectData);
            utils.when(() -> XnatUtils.getArchivableItem(EXP_ID, null)).thenReturn(imageSession);
            utils.when(() -> XnatUtils.doActionWithWorkflow(
                    any(UserI.class), any(), anyString(), any(Callable.class)))
                    .thenAnswer(inv -> {
                        ((Callable<?>) inv.getArgument(3)).call();
                        return true;
                    });
            perms.when(() -> Permissions.canRead(user, imageSession)).thenReturn(true);
            perms.when(() -> Permissions.canCreate(eq(user), anyString(), eq(DEST_PROJECT))).thenReturn(true);
            feats.when(() -> Features.checkRestrictedFeature(eq(user), anyString(), anyString())).thenReturn(true);

            final Exception thrown = processItemCatching(reimport(EXP_ID));
            assertNotNull("expected processItem to propagate a runImporter failure", thrown);
        }
    }

    // -----------------------------------------------------------------
    // Clone — saveItemAndCopyFiles after the destination-workflow collapse
    // -----------------------------------------------------------------

    /**
     * File-linking now runs without its own "Files Cloned" workflow (the destination workflow was
     * collapsed into the source-item workflow created by the copy* methods). A link failure must still
     * roll the saved item back (deleteItemWithoutSecurity) and propagate, via saveItemAndCopyFiles's
     * own catch — and no workflow is created inside this method.
     */
    @Test
    public void saveItemAndCopyFiles_linkFailure_rollsBackAndCreatesNoWorkflow() throws Exception {
        final ArchivableItem newItem = Mockito.mock(ArchivableItem.class);
        final String source = tmp.newFolder("clone-src").getAbsolutePath();
        final String dest   = tmp.getRoot().getAbsolutePath() + "/clone-dst/leaf";

        try (MockedStatic<EventUtils>     events = mockStatic(EventUtils.class);
             MockedStatic<SaveItemHelper> save   = mockStatic(SaveItemHelper.class);
             MockedStatic<FileUtil>       files  = mockStatic(FileUtil.class);
             MockedStatic<XnatUtils>      utils  = mockStatic(XnatUtils.class)) {

            files.when(() -> FileUtil.linkFiles(anyString(), anyString()))
                    .thenThrow(new RuntimeException("link boom"));

            Exception thrown = null;
            try {
                service.saveItemAndCopyFiles(user, newItem, newItem, source, dest);
            } catch (Exception e) {
                thrown = e;
            }

            assertNotNull("expected a link failure to propagate out of saveItemAndCopyFiles", thrown);
            utils.verify(() -> XnatUtils.deleteItemWithoutSecurity(newItem));
            utils.verify(() -> XnatUtils.doActionWithWorkflow(
                    any(UserI.class), any(), anyString(), any(Callable.class)), never());
        }
    }
}
