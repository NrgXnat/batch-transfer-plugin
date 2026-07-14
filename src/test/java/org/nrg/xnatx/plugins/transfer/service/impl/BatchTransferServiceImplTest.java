package org.nrg.xnatx.plugins.transfer.service.impl;

import org.nrg.xnatx.plugins.transfer.config.BatchTransferServiceConfig;
import org.nrg.xnatx.plugins.transfer.event.BatchTransferEvent;
import org.nrg.xnatx.plugins.transfer.model.BatchTransfer;
import org.nrg.xnatx.plugins.transfer.model.EventInfo;
import org.nrg.xnatx.plugins.transfer.model.TransferMode;
import org.nrg.xnatx.plugins.transfer.model.TransferRequest;
import org.nrg.xnatx.plugins.transfer.util.FileUtil;
import org.nrg.xnatx.plugins.transfer.util.XnatUtils;
import org.nrg.xnatx.plugins.transfer.jms.requests.CloneSubjectRequest;
import org.nrg.xnatx.plugins.transfer.jms.requests.TransferItemRequest;
import org.nrg.xnatx.plugins.transfer.jms.tasks.BatchTransferMonitor;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.nrg.framework.services.NrgEventService;
import org.nrg.xdat.XDAT;
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
import org.nrg.xnat.turbine.utils.ArchivableItem;
import org.nrg.xnat.helpers.prearchive.PrearcUtils;
import org.nrg.xnat.restlet.util.FileWriterWrapperI;
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
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    @Autowired private BatchTransferMonitor monitor;

    private static final String DEST_PROJECT = "destProj";
    private static final String SRC_PROJECT  = "srcProj";
    private static final String EXP_ID       = "exp1";
    private static final String LABEL        = "sess1";
    private static final String TRACKING_ID  = "t1";

    @Before
    public void setUp() throws Exception {
        Mockito.reset(service, nrgEventService, executorService, user,
                destinationProjectData, imageSession, imageAssessor, subject, monitor);

        when(user.getID()).thenReturn(42);

        // Run submitted Runnables on the test thread (used by the empty/null submit test).
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(executorService).submit(any(Runnable.class));

        when(destinationProjectData.getId()).thenReturn(DEST_PROJECT);
        when(destinationProjectData.getCachePath())
                .thenReturn(tmp.newFolder("cache").getAbsolutePath());

        // Default reimport destinations to auto-archive (AA=true); tests override with doReturn(false).
        doReturn(true).when(service).destinationAutoArchives(anyString());
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

    private void stubImageSessionAsSource() throws Exception {
        when(imageSession.getXSIType()).thenReturn("xnat:mrSessionData");
        when(imageSession.getProject()).thenReturn(SRC_PROJECT);
        when(imageSession.getId()).thenReturn(EXP_ID);
        when(imageSession.getLabel()).thenReturn(LABEL);
        when(imageSession.getCurrentSessionFolder(true))
                .thenReturn(tmp.newFolder("src").getAbsolutePath());
    }

    private static final String SUBJECT_ID    = "SUBJ1";
    private static final String SUBJECT_LABEL = "subjectLabel";

    /**
     * Drives a REIMPORT of the image session with the given preserve-label flags and returns the
     * parameter map handed to the (spy-stubbed) {@code runImporter} seam, so a test can assert which
     * label overrides ("subject" / "session") were set. {@code subjectLabel} is what the resolved
     * source subject reports as its label (pass blank to exercise the "blank ⇒ no override" path).
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> reimportCapturingImporterParams(final boolean preserveSubjectLabel,
                                                                final boolean preserveSessionLabel,
                                                                final String subjectLabel) throws Exception {
        stubImageSessionAsSource();
        when(imageSession.getProperty("subject_ID")).thenReturn(SUBJECT_ID);
        when(subject.getLabel()).thenReturn(subjectLabel);

        final List<String> importerUris = Collections.singletonList(
                "/prearchive/projects/" + DEST_PROJECT + "/20260101_000000/" + LABEL);
        doReturn(importerUris).when(service).runImporter(
                any(UserI.class), any(FileWriterWrapperI.class), any(Map.class));

        final ArgumentCaptor<Map> paramsCaptor = ArgumentCaptor.forClass(Map.class);

        try (MockedStatic<XnatUtils>   utils  = mockStatic(XnatUtils.class);
             MockedStatic<Permissions> perms  = mockStatic(Permissions.class);
             MockedStatic<Features>    feats  = mockStatic(Features.class)) {

            utils.when(() -> XnatUtils.getProject(DEST_PROJECT, user)).thenReturn(destinationProjectData);
            utils.when(() -> XnatUtils.getArchivableItem(EXP_ID, null)).thenReturn(imageSession);
            utils.when(() -> XnatUtils.getSubject(SUBJECT_ID, user)).thenReturn(subject);
            utils.when(() -> XnatUtils.doActionWithWorkflow(
                    any(UserI.class), any(), anyString(), any(Callable.class)))
                    .thenAnswer(inv -> {
                        ((Callable<?>) inv.getArgument(3)).call();
                        return true;
                    });
            perms.when(() -> Permissions.canRead(user, imageSession)).thenReturn(true);
            perms.when(() -> Permissions.canCreate(eq(user), anyString(), eq(DEST_PROJECT))).thenReturn(true);
            feats.when(() -> Features.checkRestrictedFeature(eq(user), anyString(), anyString())).thenReturn(true);

            service.processItem(new TransferRequest(DEST_PROJECT, EXP_ID, TransferMode.REIMPORT,
                    preserveSubjectLabel, preserveSessionLabel, null, null, false), user, eventInfo());

            verify(service).runImporter(eq(user), any(FileWriterWrapperI.class), paramsCaptor.capture());
            return paramsCaptor.getValue();
        }
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
     * Happy path: REIMPORT runs the (spy-stubbed) runImporter with the inline-archive params
     * (action=commit, AA=true) and queues no prearchive operation. processItem returns normally.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void importImagesession_happyPath() throws Exception {
        stubImageSessionAsSource();

        // action=commit + AA=true => importer returns archived URLs, not prearchive URIs.
        final List<String> importerUrls = Collections.singletonList(
                "/archive/experiments/" + DEST_PROJECT + "_" + LABEL);
        doReturn(importerUrls).when(service).runImporter(
                any(UserI.class), any(FileWriterWrapperI.class), any(Map.class));

        final ArgumentCaptor<Map> paramsCaptor = ArgumentCaptor.forClass(Map.class);

        try (MockedStatic<XnatUtils>   utils  = mockStatic(XnatUtils.class);
             MockedStatic<Permissions> perms  = mockStatic(Permissions.class);
             MockedStatic<Features>    feats  = mockStatic(Features.class);
             MockedStatic<PrearcUtils> prearc = mockStatic(PrearcUtils.class)) {

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

            service.processItem(reimport(EXP_ID), user, eventInfo());

            verify(service).runImporter(eq(user), any(FileWriterWrapperI.class), paramsCaptor.capture());
            assertEquals("commit", paramsCaptor.getValue().get("action"));
            assertEquals("true", paramsCaptor.getValue().get("AA"));
            // Archiving is inline; no prearchive operation is queued.
            prearc.verify(() -> PrearcUtils.queuePrearchiveOperation(any()), never());
        }
    }

    /** A Manual destination sets action=commit but not AA, leaving the built session in the prearchive. */
    @Test
    @SuppressWarnings("unchecked")
    public void importImagesession_manualDestination_omitsAutoArchive() throws Exception {
        stubImageSessionAsSource();
        doReturn(false).when(service).destinationAutoArchives(anyString());

        final List<String> importerUris = Collections.singletonList(
                "/prearchive/projects/" + DEST_PROJECT + "/20260101_000000/" + LABEL);
        doReturn(importerUris).when(service).runImporter(
                any(UserI.class), any(FileWriterWrapperI.class), any(Map.class));

        final ArgumentCaptor<Map> paramsCaptor = ArgumentCaptor.forClass(Map.class);

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

            service.processItem(reimport(EXP_ID), user, eventInfo());

            verify(service).runImporter(eq(user), any(FileWriterWrapperI.class), paramsCaptor.capture());
            assertEquals("commit", paramsCaptor.getValue().get("action"));
            assertTrue("Manual destination must not set AA", !paramsCaptor.getValue().containsKey("AA"));
        }
    }

    /**
     * Preserve-label flags (issue #10): each flag independently adds a label override to the importer
     * params — "subject" (→ SUBJECT_ID) from the source subject label and "session" (→ EXPT_LABEL) from
     * the source session label — so the destination uses the source XNAT labels instead of deriving them
     * from DICOM tags. The four flag combinations are asserted via the captured runImporter params.
     */
    @Test
    public void preserveLabels_both_addsSubjectAndSessionOverrides() throws Exception {
        final Map<String, Object> params = reimportCapturingImporterParams(true, true, SUBJECT_LABEL);
        assertEquals(SUBJECT_LABEL, params.get("subject"));
        assertEquals(LABEL, params.get("session"));
    }

    @Test
    public void preserveLabels_subjectOnly_addsSubjectOverrideOnly() throws Exception {
        final Map<String, Object> params = reimportCapturingImporterParams(true, false, SUBJECT_LABEL);
        assertEquals(SUBJECT_LABEL, params.get("subject"));
        assertTrue("session override must not be set when only subject is preserved",
                !params.containsKey("session"));
    }

    @Test
    public void preserveLabels_sessionOnly_addsSessionOverrideOnly() throws Exception {
        final Map<String, Object> params = reimportCapturingImporterParams(false, true, SUBJECT_LABEL);
        assertEquals(LABEL, params.get("session"));
        assertTrue("subject override must not be set when only session is preserved",
                !params.containsKey("subject"));
    }

    @Test
    public void preserveLabels_none_addsNoOverrides() throws Exception {
        final Map<String, Object> params = reimportCapturingImporterParams(false, false, SUBJECT_LABEL);
        assertTrue("subject override must not be set", !params.containsKey("subject"));
        assertTrue("session override must not be set", !params.containsKey("session"));
    }

    /**
     * Drives a REIMPORT that preserves only the subject label, with the resolved source subject
     * reporting {@code subjectLabel}, and returns the thrown exception (or null). Used for the
     * blank/missing-label error cases; asserts the importer is never invoked.
     */
    private Exception reimportExpectingSubjectLabelError(final String subjectLabel) throws Exception {
        stubImageSessionAsSource();
        when(imageSession.getProperty("subject_ID")).thenReturn(SUBJECT_ID);
        when(subject.getLabel()).thenReturn(subjectLabel);

        try (MockedStatic<XnatUtils>   utils = mockStatic(XnatUtils.class);
             MockedStatic<Permissions> perms = mockStatic(Permissions.class);
             MockedStatic<Features>    feats = mockStatic(Features.class)) {

            utils.when(() -> XnatUtils.getProject(DEST_PROJECT, user)).thenReturn(destinationProjectData);
            utils.when(() -> XnatUtils.getArchivableItem(EXP_ID, null)).thenReturn(imageSession);
            utils.when(() -> XnatUtils.getSubject(SUBJECT_ID, user)).thenReturn(subject);
            perms.when(() -> Permissions.canRead(user, imageSession)).thenReturn(true);
            perms.when(() -> Permissions.canCreate(eq(user), anyString(), eq(DEST_PROJECT))).thenReturn(true);
            feats.when(() -> Features.checkRestrictedFeature(eq(user), anyString(), anyString())).thenReturn(true);

            final Exception thrown = processItemCatching(
                    new TransferRequest(DEST_PROJECT, EXP_ID, TransferMode.REIMPORT, true, false, null, null, false));
            verify(service, never()).runImporter(any(UserI.class), any(FileWriterWrapperI.class), any(Map.class));
            return thrown;
        }
    }

    /**
     * Drives a REIMPORT that preserves only the session label, with the source session reporting
     * {@code sessionLabel}, and returns the thrown exception (or null). Asserts the importer is never invoked.
     */
    private Exception reimportExpectingSessionLabelError(final String sessionLabel) throws Exception {
        when(imageSession.getXSIType()).thenReturn("xnat:mrSessionData");
        when(imageSession.getProject()).thenReturn(SRC_PROJECT);
        when(imageSession.getId()).thenReturn(EXP_ID);
        when(imageSession.getLabel()).thenReturn(sessionLabel);
        when(imageSession.getCurrentSessionFolder(true)).thenReturn(tmp.newFolder().getAbsolutePath());

        try (MockedStatic<XnatUtils>   utils = mockStatic(XnatUtils.class);
             MockedStatic<Permissions> perms = mockStatic(Permissions.class);
             MockedStatic<Features>    feats = mockStatic(Features.class)) {

            utils.when(() -> XnatUtils.getProject(DEST_PROJECT, user)).thenReturn(destinationProjectData);
            utils.when(() -> XnatUtils.getArchivableItem(EXP_ID, null)).thenReturn(imageSession);
            perms.when(() -> Permissions.canRead(user, imageSession)).thenReturn(true);
            perms.when(() -> Permissions.canCreate(eq(user), anyString(), eq(DEST_PROJECT))).thenReturn(true);
            feats.when(() -> Features.checkRestrictedFeature(eq(user), anyString(), anyString())).thenReturn(true);

            final Exception thrown = processItemCatching(
                    new TransferRequest(DEST_PROJECT, EXP_ID, TransferMode.REIMPORT, false, true, null, null, false));
            verify(service, never()).runImporter(any(UserI.class), any(FileWriterWrapperI.class), any(Map.class));
            return thrown;
        }
    }

    /**
     * Preserve subject/session label requested but the source label is blank or missing (null) → the
     * substitution has nothing usable, so processItem fails the item rather than skipping it. All four
     * combinations (subject/session × null/blank) throw and never reach the importer.
     */
    @Test
    public void preserveLabels_nullSubjectLabel_throws() throws Exception {
        final Exception thrown = reimportExpectingSubjectLabelError(null);
        assertNotNull("expected processItem to throw when the source subject label is null", thrown);
        assertTrue("message should mention the blank/missing subject label: " + thrown.getMessage(),
                thrown.getMessage() != null && thrown.getMessage().contains("subject label is blank or missing"));
    }

    @Test
    public void preserveLabels_blankSubjectLabel_throws() throws Exception {
        final Exception thrown = reimportExpectingSubjectLabelError("  ");
        assertNotNull("expected processItem to throw when the source subject label is blank", thrown);
        assertTrue("message should mention the blank/missing subject label: " + thrown.getMessage(),
                thrown.getMessage() != null && thrown.getMessage().contains("subject label is blank or missing"));
    }

    @Test
    public void preserveLabels_nullSessionLabel_throws() throws Exception {
        final Exception thrown = reimportExpectingSessionLabelError(null);
        assertNotNull("expected processItem to throw when the source session label is null", thrown);
        assertTrue("message should mention the blank/missing session label: " + thrown.getMessage(),
                thrown.getMessage() != null && thrown.getMessage().contains("session label is blank or missing"));
    }

    @Test
    public void preserveLabels_blankSessionLabel_throws() throws Exception {
        final Exception thrown = reimportExpectingSessionLabelError("  ");
        assertNotNull("expected processItem to throw when the source session label is blank", thrown);
        assertTrue("message should mention the blank/missing session label: " + thrown.getMessage(),
                thrown.getMessage() != null && thrown.getMessage().contains("session label is blank or missing"));
    }

    /**
     * A Reimport whose request omits the preserve-label flags (the 3-arg constructor leaves both
     * {@code null} — as a direct API call or pre-flag client would) must not throw and must add no
     * label overrides. Guards the {@code Boolean.TRUE.equals(...)} null check in importExperiment: a
     * "simplification" to {@code if (preserveSubjectLabel)} would NPE here while passing every other test.
     */
    @Test
    public void preserveLabels_nullFlags_addsNoOverridesAndDoesNotThrow() throws Exception {
        stubImageSessionAsSource();

        final List<String> importerUris = Collections.singletonList(
                "/prearchive/projects/" + DEST_PROJECT + "/20260101_000000/" + LABEL);
        doReturn(importerUris).when(service).runImporter(
                any(UserI.class), any(FileWriterWrapperI.class), any(Map.class));

        final ArgumentCaptor<Map> paramsCaptor = ArgumentCaptor.forClass(Map.class);

        try (MockedStatic<XnatUtils>   utils  = mockStatic(XnatUtils.class);
             MockedStatic<Permissions> perms  = mockStatic(Permissions.class);
             MockedStatic<Features>    feats  = mockStatic(Features.class)) {

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

            // reimport(id) uses the 3-arg constructor, so both preserve flags are null.
            service.processItem(reimport(EXP_ID), user, eventInfo());

            verify(service).runImporter(eq(user), any(FileWriterWrapperI.class), paramsCaptor.capture());
            assertTrue("null preserveSubjectLabel must add no subject override",
                    !paramsCaptor.getValue().containsKey("subject"));
            assertTrue("null preserveSessionLabel must add no session override",
                    !paramsCaptor.getValue().containsKey("session"));
        }
    }

    /**
     * A REIMPORT carrying a custom anon script forwards it to the importer as the
     * {@code Anon-Script} param. (A request without one adds no such key — see the happy-path test, whose
     * captured params never contain it.)
     */
    @Test
    @SuppressWarnings("unchecked")
    public void importImagesession_customAnonScript_passedToImporterParams() throws Exception {
        stubImageSessionAsSource();

        final String script = "version \"6.1\"\n(0010,0010) := \"ANON\"";
        // action=commit + AA=true => importer returns archived URLs (inline archive, no prearchive commit).
        final List<String> importerUrls = Collections.singletonList(
                "/archive/experiments/" + DEST_PROJECT + "_" + LABEL);
        doReturn(importerUrls).when(service).runImporter(
                any(UserI.class), any(FileWriterWrapperI.class), any(Map.class));

        final ArgumentCaptor<Map> paramsCaptor = ArgumentCaptor.forClass(Map.class);

        try (MockedStatic<XnatUtils>   utils  = mockStatic(XnatUtils.class);
             MockedStatic<Permissions> perms  = mockStatic(Permissions.class);
             MockedStatic<Features>    feats  = mockStatic(Features.class)) {

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

            final TransferRequest withScript = reimport(EXP_ID);
            withScript.setAnonScript(script);
            service.processItem(withScript, user, eventInfo());

            verify(service).runImporter(eq(user), any(FileWriterWrapperI.class), paramsCaptor.capture());
            assertEquals(script, paramsCaptor.getValue().get("Anon-Script"));
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

    // -----------------------------------------------------------------
    // batchTransfer — split a mixed batch by operation
    // -----------------------------------------------------------------

    /**
     * A batch can mix operations. batchTransfer must split by operation and route each to its path —
     * Reimport → reimport queue, Clone → clone queue (grouped by subject), Share → in-process — while
     * registering the batch with the monitor exactly once (one terminal event for the whole batch).
     */
    @Test
    public void batchTransfer_mixedBatch_splitsByOperation() throws Exception {
        final TransferRequest reimportReq = new TransferRequest(DEST_PROJECT, "exp_reimport", TransferMode.REIMPORT);
        final TransferRequest cloneReq1   = new TransferRequest(DEST_PROJECT, "exp_clone1",   TransferMode.CLONE);
        final TransferRequest cloneReq2   = new TransferRequest(DEST_PROJECT, "exp_clone2",   TransferMode.CLONE);
        final TransferRequest shareReq    = new TransferRequest(DEST_PROJECT, "exp_share",    TransferMode.SHARE);
        final List<TransferRequest> all = new ArrayList<>();
        all.add(reimportReq); all.add(cloneReq1); all.add(cloneReq2); all.add(shareReq);
        final BatchTransfer batch = new BatchTransfer(all, TRACKING_ID);

        // Share items run in-process via processItem; stub it (the service is a spy) so this router test
        // doesn't execute the real share logic. Reimport/Clone items are enqueued, not processed here.
        Mockito.doNothing().when(service).processItem(any(TransferRequest.class), any(UserI.class), any(EventInfo.class));

        try (MockedStatic<XDAT>      xdat  = mockStatic(XDAT.class);
             MockedStatic<XnatUtils> utils = mockStatic(XnatUtils.class)) {

            // Clone grouping resolves each clone item's subject (both → SUBJ1, so one bundle of two).
            utils.when(() -> XnatUtils.getArchivableItem("exp_clone1", null)).thenReturn(imageSession);
            utils.when(() -> XnatUtils.getArchivableItem("exp_clone2", null)).thenReturn(imageSession);
            when(imageSession.getProperty("subject_ID")).thenReturn("SUBJ1");

            service.submitTransferRequest(batch, user); // mock executor runs batchTransfer on this thread

            // Registered once for the whole batch (all four items) → a single terminal event.
            verify(monitor).register(TRACKING_ID, 42, 4);

            // Two JMS sends: one reimport item, one clone bundle.
            final ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
            xdat.verify(() -> XDAT.sendJmsRequest(sent.capture()), times(2));

            final TransferItemRequest reimportSent = sent.getAllValues().stream()
                    .filter(r -> r instanceof TransferItemRequest).map(r -> (TransferItemRequest) r)
                    .findFirst().orElse(null);
            assertNotNull("reimport item should be queued to the reimport queue", reimportSent);
            assertEquals("exp_reimport", reimportSent.getItemId());

            final CloneSubjectRequest cloneSent = sent.getAllValues().stream()
                    .filter(r -> r instanceof CloneSubjectRequest).map(r -> (CloneSubjectRequest) r)
                    .findFirst().orElse(null);
            assertNotNull("clone items should be queued to the clone queue", cloneSent);
            assertEquals("SUBJ1", cloneSent.getSubjectId());
            assertEquals(2, cloneSent.getItems().size());

            // Share item is processed in-process; reimport/clone items were enqueued, not processed.
            final ArgumentCaptor<TransferRequest> processed = ArgumentCaptor.forClass(TransferRequest.class);
            verify(service).processItem(processed.capture(), any(UserI.class), any(EventInfo.class));
            assertEquals("exp_share", processed.getValue().getId());
            assertEquals(TransferMode.SHARE, processed.getValue().getMode());
        }
    }
}
