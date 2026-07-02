package org.nrg.xnatx.plugins.transfer.jms.tasks;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.nrg.framework.services.NrgEventService;
import org.nrg.xnatx.plugins.transfer.event.BatchTransferEvent;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferProgressService;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferProgressService.Completion;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests the terminal-event decision in {@link BatchTransferMonitor} against a mocked service; the
 * service's atomic increment/locking needs a real Postgres and is out of scope here.
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

    @Test
    public void itemDone_notCompleting_firesNothing() {
        // Service returns null for any item that did not bring the batch to its total (or unknown batch).
        when(progressService.recordItemDone(TRACKING_ID, false)).thenReturn(null);

        monitor.itemDone(TRACKING_ID, false);

        verifyNoInteractions(eventService);
    }

    @Test
    public void itemDone_completing_noFailures_firesCompleted() {
        when(progressService.recordItemDone(TRACKING_ID, false)).thenReturn(new Completion(USER_ID, 0));

        monitor.itemDone(TRACKING_ID, false);

        final ArgumentCaptor<BatchTransferEvent> captor = ArgumentCaptor.forClass(BatchTransferEvent.class);
        verify(eventService).triggerEvent(captor.capture());
        final BatchTransferEvent event = captor.getValue();
        assertEquals(BatchTransferEvent.Status.Completed, event.getStatus());
        assertEquals(TRACKING_ID, event.getTrackingId());
        assertEquals(USER_ID, event.getUserId());
    }

    @Test
    public void itemDone_completing_withFailures_firesWarning() {
        when(progressService.recordItemDone(TRACKING_ID, true)).thenReturn(new Completion(USER_ID, 2));

        monitor.itemDone(TRACKING_ID, true);

        final ArgumentCaptor<BatchTransferEvent> captor = ArgumentCaptor.forClass(BatchTransferEvent.class);
        verify(eventService).triggerEvent(captor.capture());
        assertEquals(BatchTransferEvent.Status.Warning, captor.getValue().getStatus());
    }

    @Test
    public void register_delegatesToService() {
        monitor.register(TRACKING_ID, USER_ID, 10);
        verify(progressService).register(TRACKING_ID, USER_ID, 10);
    }
}
