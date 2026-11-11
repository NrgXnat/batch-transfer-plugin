package org.nrg.xnatx.plugins.transfer.api;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.nrg.framework.services.NrgEventService;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.security.UserI;
import org.nrg.xnatx.plugins.transfer.event.BatchTransferEvent;
import org.nrg.xnatx.plugins.transfer.jms.tasks.entities.BatchTransferHistory;
import org.nrg.xnatx.plugins.transfer.jms.tasks.entities.BatchTransferItem;
import org.nrg.xnatx.plugins.transfer.jms.tasks.entities.BatchTransferProgress;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferHistoryService;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferHistoryService.StopResult;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferProgressService;
import org.nrg.xnatx.plugins.transfer.model.TransferRun;
import org.nrg.xnatx.plugins.transfer.model.TransferRunDetail;
import org.nrg.xnatx.plugins.transfer.model.TransferRunItem;
import org.nrg.xnatx.plugins.transfer.model.TransferRunPage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The history API's rules, with the services mocked and XNAT's permission checks stubbed through the controller's
 * protected seams: who can see a run, who gets its values and script, who can stop it, and how list scopes work.
 */
public class BatchTransferHistoryApiTest {

    private static final String TRACKING_ID = "batch_transfer_1";
    private static final String SOURCE      = "SRC";
    private static final String DESTINATION = "DST";

    private BatchTransferHistoryService  historyService;
    private BatchTransferProgressService progressService;
    private NrgEventService              eventService;
    private UserI                        user;
    private BatchTransferHistoryApi      api;

    @Before
    public void setUp() {
        historyService  = mock(BatchTransferHistoryService.class);
        progressService = mock(BatchTransferProgressService.class);
        eventService    = mock(NrgEventService.class);
        user            = mock(UserI.class);
        when(user.getUsername()).thenReturn("carol");

        api = spy(new BatchTransferHistoryApi(mock(UserManagementServiceI.class), mock(RoleHolder.class),
                historyService, progressService, eventService));
        final Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(user);
        SecurityContextHolder.getContext().setAuthentication(auth);

        // By default the caller is no admin and has no access to the source project.
        doReturn(false).when(api).isSiteAdmin(any());
        doReturn(false).when(api).canEditProject(any(), anyString());
        doReturn(Collections.emptyList()).when(api).readableProjects(any());
    }

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---- detail ----------------------------------------------------------------------------------------

    @Test
    public void detail_unknownRun_is404() {
        assertEquals(HttpStatus.NOT_FOUND, api.detail(TRACKING_ID).getStatusCode());
    }

    @Test
    public void detail_aRunTheCallerCantSee_is403() {
        stubRun("alice", BatchTransferHistory.Status.Running);
        assertEquals(HttpStatus.FORBIDDEN, api.detail(TRACKING_ID).getStatusCode());
    }

    @Test
    public void detail_aSourceReaderSeesTheRunWithoutValuesOrScript() {
        stubRun("alice", BatchTransferHistory.Status.Completed);
        doReturn(Arrays.asList(SOURCE, DESTINATION)).when(api).readableProjects(any());

        final TransferRunDetail detail = body(api.detail(TRACKING_ID));

        assertEquals(TRACKING_ID, detail.getRun().getTrackingId());
        assertNull(detail.getAnonScript());
        assertNull(detail.getItems().get(0).getCsvValues());
        assertEquals("XNAT_E9", detail.getItems().get(0).getDestinationId());
        assertEquals("THE_LABEL", detail.getItems().get(0).getDestinationLabel());
        assertEquals("ROUTED_S", detail.getItems().get(0).getDestinationSubjectLabel());
        assertNull(detail.getItems().get(0).getDestinationHidden());
        assertFalse(detail.isCanStop());
    }

    @Test
    public void detail_aReaderWhoCantReadTheDestinationGetsNoDestinationIdOrLabels() {
        stubRun("alice", BatchTransferHistory.Status.Completed);
        doReturn(Collections.singletonList(SOURCE)).when(api).readableProjects(any());

        final TransferRunItem item = this.<TransferRunDetail>body(api.detail(TRACKING_ID)).getItems().get(0);

        assertEquals("XNAT_E1", item.getItemId());
        assertEquals(DESTINATION, item.getDestinationProject());
        assertNull(item.getDestinationId());
        assertNull(item.getDestinationLabel());
        assertNull(item.getDestinationSubjectLabel());
        assertTrue("the client can tell hidden from missing", item.getDestinationHidden());
        verify(historyService).itemLabels(Collections.emptySet());
    }

    @Test
    public void detail_theCreatorSeesDestinationsWithoutReadingTheDestination() {
        stubRun("carol", BatchTransferHistory.Status.Completed);

        final TransferRunItem item = this.<TransferRunDetail>body(api.detail(TRACKING_ID)).getItems().get(0);

        assertEquals("XNAT_E9", item.getDestinationId());
        assertEquals("THE_LABEL", item.getDestinationLabel());
        verify(api, never()).readableProjects(any());
    }

    @Test
    public void detail_aSourceEditorAndTheCreatorSeeValuesAndScript() {
        stubRun("alice", BatchTransferHistory.Status.Running);
        doReturn(Collections.singletonList(SOURCE)).when(api).readableProjects(any());
        doReturn(true).when(api).canEditProject(any(), eq(SOURCE));
        TransferRunDetail detail = body(api.detail(TRACKING_ID));
        assertEquals("the script", detail.getAnonScript());
        assertEquals("ANON_1", detail.getItems().get(0).getCsvValues().get("pid"));
        assertFalse("only the creator or an admin can stop", detail.isCanStop());

        when(user.getUsername()).thenReturn("alice");
        doReturn(false).when(api).canEditProject(any(), anyString());
        detail = body(api.detail(TRACKING_ID));
        assertEquals("the script", detail.getAnonScript());
        assertTrue(detail.isCanStop());
    }

    @Test
    public void detail_anUnfinishedRunShowsItsLiveCounts() {
        stubRun("carol", BatchTransferHistory.Status.Running);
        final BatchTransferProgress progress = new BatchTransferProgress(TRACKING_ID, 7, 10);
        progress.setCompleted(6);
        progress.setFailed(1);
        progress.setStopped(2);
        when(progressService.findByTrackingId(TRACKING_ID)).thenReturn(progress);

        final TransferRun run = this.<TransferRunDetail>body(api.detail(TRACKING_ID)).getRun();

        assertEquals(3, run.getPassedCount());
        assertEquals(1, run.getFailedCount());
        assertEquals(2, run.getStoppedCount());
    }

    // ---- stop ------------------------------------------------------------------------------------------

    @Test
    public void stop_byAnotherUser_is403() {
        stubRun("alice", BatchTransferHistory.Status.Running);
        doReturn(Collections.singletonList(SOURCE)).when(api).readableProjects(any());
        assertEquals(HttpStatus.FORBIDDEN, api.stop(TRACKING_ID).getStatusCode());
        verify(historyService, never()).requestStop(anyString(), anyString());
    }

    @Test
    public void stop_byTheCreator_stopsAndTellsTheActivityMonitor() {
        stubRun("carol", BatchTransferHistory.Status.Running);
        when(historyService.requestStop(TRACKING_ID, "carol")).thenReturn(StopResult.STOPPING);

        assertEquals(HttpStatus.OK, api.stop(TRACKING_ID).getStatusCode());
        verify(eventService).triggerEvent(any(BatchTransferEvent.class));
    }

    @Test
    public void stop_byAnAdmin_isAllowed() {
        stubRun("alice", BatchTransferHistory.Status.Queued);
        doReturn(true).when(api).isSiteAdmin(any());
        when(historyService.requestStop(TRACKING_ID, "carol")).thenReturn(StopResult.ALREADY_STOPPING);
        assertEquals(HttpStatus.OK, api.stop(TRACKING_ID).getStatusCode());
        verify(eventService, never()).triggerEvent(any(BatchTransferEvent.class));
    }

    @Test
    public void stop_aFinishedRun_is409() {
        stubRun("carol", BatchTransferHistory.Status.Completed);
        when(historyService.requestStop(TRACKING_ID, "carol")).thenReturn(StopResult.FINISHED);
        assertEquals(HttpStatus.CONFLICT, api.stop(TRACKING_ID).getStatusCode());
    }

    @Test
    public void stop_anUnknownRun_is404() {
        assertEquals(HttpStatus.NOT_FOUND, api.stop(TRACKING_ID).getStatusCode());
    }

    // ---- list ------------------------------------------------------------------------------------------

    @Test
    public void list_withoutAProject_showsTheCallersOwnRuns() {
        api.list(null, null, 0, 25);
        verify(historyService).list(isNull(), eq("carol"), isNull(), eq("carol"), anyCollection(), eq(0), eq(25));
    }

    @Test
    public void list_forAProject_filtersByWhatTheCallerCanRead() {
        doReturn(Arrays.asList(SOURCE, "OTHER")).when(api).readableProjects(any());
        api.list(SOURCE, "Running", 2, 10);
        verify(historyService).list(SOURCE, null, BatchTransferHistory.Status.Running, "carol",
                Arrays.asList(SOURCE, "OTHER"), 20, 10);
    }

    @Test
    public void list_forAnAdmin_isNotFiltered() {
        doReturn(true).when(api).isSiteAdmin(any());
        api.list(SOURCE, null, 0, 500);
        verify(historyService).list(eq(SOURCE), isNull(), isNull(), isNull(), isNull(), eq(0), eq(200));
    }

    @Test
    public void list_anUnknownStatus_is400() {
        assertEquals(HttpStatus.BAD_REQUEST, api.list(null, "Done", 0, 25).getStatusCode());
        verify(historyService, never()).list(any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    public void list_returnsAPage() {
        final BatchTransferHistory run = run("carol", BatchTransferHistory.Status.Completed);
        when(historyService.list(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Collections.singletonList(run));
        when(historyService.count(any(), any(), any(), any(), any())).thenReturn(31L);

        final TransferRunPage page = body(api.list(null, null, 1, 25));

        assertEquals(31L, page.getTotal());
        assertEquals(1, page.getPage());
        assertEquals(TRACKING_ID, page.getRuns().get(0).getTrackingId());
        assertTrue(page.getRuns().get(0).isCustomScript());
    }

    @Test
    public void list_aPagePastTheLargestOffset_isEmpty() {
        when(historyService.count(any(), any(), any(), any(), any())).thenReturn(31L);

        final TransferRunPage page = body(api.list(null, null, Integer.MAX_VALUE, 200));

        assertTrue(page.getRuns().isEmpty());
        assertEquals(31L, page.getTotal());
        verify(historyService, never()).list(any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    // ---- fixtures --------------------------------------------------------------------------------------

    private void stubRun(final String createdBy, final BatchTransferHistory.Status status) {
        final BatchTransferHistory run = run(createdBy, status);
        when(historyService.findByTrackingId(TRACKING_ID)).thenReturn(run);
        final BatchTransferItem item = new BatchTransferItem();
        item.setItemId("XNAT_E1");
        item.setStatus(BatchTransferItem.Status.Done);
        item.setCsvValues("{\"pid\":\"ANON_1\"}");
        item.setDestinationProject(DESTINATION);
        item.setDestinationSubjectLabel("ROUTED_S");
        item.setDestinationId("XNAT_E9");
        final List<BatchTransferItem> items = Collections.singletonList(item);
        when(historyService.items(run.getId())).thenReturn(items);
        when(historyService.itemLabels(any())).thenReturn(Collections.singletonMap("XNAT_E9", "THE_LABEL"));
    }

    private static BatchTransferHistory run(final String createdBy, final BatchTransferHistory.Status status) {
        final BatchTransferHistory run = new BatchTransferHistory();
        run.setId(5L);
        run.setTrackingId(TRACKING_ID);
        run.setStatus(status);
        run.setSourceProject(SOURCE);
        run.setCreatedBy(createdBy);
        run.setUserId(7);
        run.setItemCount(10);
        run.setAnonCustomScript("the script");
        return run;
    }

    @SuppressWarnings("unchecked")
    private <T> T body(final ResponseEntity<?> response) {
        assertEquals(HttpStatus.OK, response.getStatusCode());
        return (T) response.getBody();
    }
}
