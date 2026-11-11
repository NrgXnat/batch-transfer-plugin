package org.nrg.xnatx.plugins.transfer.jms.listeners;

import org.junit.Before;
import org.junit.Test;
import org.nrg.framework.services.NrgEventService;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.security.UserI;
import org.nrg.xnatx.plugins.transfer.jms.requests.CloneSubjectRequest;
import org.nrg.xnatx.plugins.transfer.jms.requests.TransferItemRequest;
import org.nrg.xnatx.plugins.transfer.jms.tasks.BatchTransferMonitor;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferProgressService.Outcome;
import org.nrg.xnatx.plugins.transfer.model.EventInfo;
import org.nrg.xnatx.plugins.transfer.model.TransferRequest;
import org.nrg.xnatx.plugins.transfer.service.BatchTransferService;

import java.util.Arrays;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The two queue consumers ask the monitor before each item and report each item's outcome exactly once: a stopped
 * item is skipped without being finished, and an item that fails before its work starts is finished as failed.
 */
public class ItemListenersTest {

    private static final String TRACKING_ID = "batch_transfer_1";

    private BatchTransferService   service;
    private UserManagementServiceI users;
    private BatchTransferMonitor   monitor;
    private UserI                  user;

    @Before
    public void setUp() throws Exception {
        service = mock(BatchTransferService.class);
        users   = mock(UserManagementServiceI.class);
        monitor = mock(BatchTransferMonitor.class);
        user    = mock(UserI.class);
        when(users.getUser("alice")).thenReturn(user);
    }

    // ---- Reimport ------------------------------------------------------------------------------------------

    @Test
    public void reimport_aStoppedItemIsSkipped() throws Exception {
        when(monitor.startItem(TRACKING_ID, "E1")).thenReturn(false);

        reimportListener().onRequest(reimportRequest("E1"));

        verifyNoInteractions(service, users);
        verify(monitor, never()).finishItem(anyString(), anyString(), any(), any(), any());
    }

    @Test
    public void reimport_reportsTheDestination() throws Exception {
        when(monitor.startItem(TRACKING_ID, "E1")).thenReturn(true);
        when(service.processItem(any(TransferRequest.class), eq(user), any(EventInfo.class))).thenReturn("E9");

        reimportListener().onRequest(reimportRequest("E1"));

        verify(monitor).finishItem(TRACKING_ID, "E1", Outcome.DONE, null, "E9");
    }

    @Test
    public void reimport_reportsAFailure() throws Exception {
        when(monitor.startItem(TRACKING_ID, "E1")).thenReturn(true);
        when(service.processItem(any(TransferRequest.class), eq(user), any(EventInfo.class)))
                .thenThrow(new Exception("No DICOM files found"));

        reimportListener().onRequest(reimportRequest("E1"));

        verify(monitor).finishItem(eq(TRACKING_ID), eq("E1"), eq(Outcome.FAILED),
                argThat(message -> message.contains("No DICOM files found")), isNull());
    }

    @Test
    public void reimport_aUserThatCantBeLoadedFailsTheItem() throws Exception {
        when(monitor.startItem(TRACKING_ID, "E1")).thenReturn(true);
        when(users.getUser("alice")).thenThrow(new RuntimeException("no such user"));

        reimportListener().onRequest(reimportRequest("E1"));

        verify(monitor).finishItem(TRACKING_ID, "E1", Outcome.FAILED, "Could not load user alice", null);
        verifyNoInteractions(service);
    }

    // ---- Clone ---------------------------------------------------------------------------------------------

    @Test
    public void clone_aStopPartWayThroughABundleSkipsTheRest() throws Exception {
        when(monitor.startItem(TRACKING_ID, "E1")).thenReturn(true);
        when(monitor.startItem(TRACKING_ID, "E2")).thenReturn(false);
        when(monitor.startItem(TRACKING_ID, "E3")).thenReturn(false);
        when(service.processItem(any(TransferRequest.class), eq(user), any(EventInfo.class))).thenReturn("C1");

        cloneListener().onRequest(cloneRequest("E1", "E2", "E3"));

        verify(service, times(1)).processItem(any(TransferRequest.class), eq(user), any(EventInfo.class));
        verify(monitor).finishItem(TRACKING_ID, "E1", Outcome.DONE, null, "C1");
        verify(monitor, times(1)).finishItem(anyString(), anyString(), any(), any(), any());
    }

    @Test
    public void clone_aUserThatCantBeLoadedFailsEveryItem() throws Exception {
        when(users.getUser("alice")).thenThrow(new RuntimeException("no such user"));

        cloneListener().onRequest(cloneRequest("E1", "E2"));

        verify(monitor).finishItem(TRACKING_ID, "E1", Outcome.FAILED, "Could not load user alice", null);
        verify(monitor).finishItem(TRACKING_ID, "E2", Outcome.FAILED, "Could not load user alice", null);
        verifyNoInteractions(service);
    }

    // ---- fixtures ------------------------------------------------------------------------------------------

    private TransferReimportListener reimportListener() {
        return new TransferReimportListener(service, users, mock(NrgEventService.class), monitor);
    }

    private CloneSubjectListener cloneListener() {
        return new CloneSubjectListener(service, users, mock(NrgEventService.class), monitor);
    }

    private static TransferItemRequest reimportRequest(final String itemId) {
        return new TransferItemRequest(TRACKING_ID, itemId, "DEST", "alice", 7, true, true, null, null, null, false);
    }

    private static CloneSubjectRequest cloneRequest(final String... itemIds) {
        final CloneSubjectRequest.CloneItem[] items = new CloneSubjectRequest.CloneItem[itemIds.length];
        for (int i = 0; i < itemIds.length; i++) {
            items[i] = new CloneSubjectRequest.CloneItem(itemIds[i], "DEST");
        }
        return new CloneSubjectRequest(TRACKING_ID, "S1", Arrays.asList(items), "alice", 7);
    }
}
