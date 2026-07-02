package org.nrg.xnatx.plugins.transfer.jms.tasks;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.nrg.framework.services.NrgEventService;
import org.nrg.xnatx.plugins.transfer.event.BatchTransferEvent;
import org.nrg.xnatx.plugins.transfer.jms.tasks.entities.BatchTransferProgress;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferProgressService;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests the terminal-event decision in {@link BatchTransferMonitor} against a mocked service; the
 * service's SQL/locking needs a real Postgres and is out of scope here.
 */
public class BatchTransferMonitorTest {

    private static final String  TRACKING_ID = "batch_transfer_123";
    private static final Integer USER_ID     = 42;

    private NrgEventService              eventService;
    private BatchTransferProgressService progressService;
    private BatchTransferMonitor         monitor;

    @Before
    public void setUp() {
        eventService    = mock(NrgEventService.class);
        progressService = mock(BatchTransferProgressService.class);
        monitor         = new BatchTransferMonitor(eventService, progressService);
    }

    private static BatchTransferProgress progress(final int completed, final int total, final int failed) {
        final BatchTransferProgress p = new BatchTransferProgress(TRACKING_ID, USER_ID, total);
        p.setCompleted(completed);
        p.setFailed(failed);
        return p;
    }

    @Test
    public void itemDone_notLastItem_firesNothing() {
        when(progressService.recordItemDone(TRACKING_ID, false)).thenReturn(progress(3, 10, 0));

        monitor.itemDone(TRACKING_ID, false);

        verifyNoInteractions(eventService);
        verify(progressService, never()).remove(TRACKING_ID);
    }

    @Test
    public void itemDone_unknownBatch_firesNothing() {
        when(progressService.recordItemDone(TRACKING_ID, false)).thenReturn(null);

        monitor.itemDone(TRACKING_ID, false);

        verifyNoInteractions(eventService);
        verify(progressService, never()).remove(TRACKING_ID);
    }

    @Test
    public void itemDone_lastItem_noFailures_firesCompletedAndRemoves() {
        when(progressService.recordItemDone(TRACKING_ID, false)).thenReturn(progress(10, 10, 0));

        monitor.itemDone(TRACKING_ID, false);

        final ArgumentCaptor<BatchTransferEvent> captor = ArgumentCaptor.forClass(BatchTransferEvent.class);
        verify(eventService).triggerEvent(captor.capture());
        final BatchTransferEvent event = captor.getValue();
        assertEquals(BatchTransferEvent.Status.Completed, event.getStatus());
        assertEquals(TRACKING_ID, event.getTrackingId());
        assertEquals(USER_ID, event.getUserId());
        verify(progressService).remove(TRACKING_ID);
    }

    @Test
    public void itemDone_lastItem_withFailures_firesWarningAndRemoves() {
        when(progressService.recordItemDone(TRACKING_ID, true)).thenReturn(progress(10, 10, 2));

        monitor.itemDone(TRACKING_ID, true);

        final ArgumentCaptor<BatchTransferEvent> captor = ArgumentCaptor.forClass(BatchTransferEvent.class);
        verify(eventService).triggerEvent(captor.capture());
        assertEquals(BatchTransferEvent.Status.Warning, captor.getValue().getStatus());
        verify(progressService).remove(TRACKING_ID);
    }

    @Test
    public void register_and_currentPercent_delegateToService() {
        monitor.register(TRACKING_ID, USER_ID, 10);
        verify(progressService).register(TRACKING_ID, USER_ID, 10);

        when(progressService.currentPercent(TRACKING_ID)).thenReturn(37);
        assertEquals(37, monitor.currentPercent(TRACKING_ID));
    }
}
