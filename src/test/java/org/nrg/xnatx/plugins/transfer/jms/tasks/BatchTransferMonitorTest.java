package org.nrg.xnatx.plugins.transfer.jms.tasks;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.nrg.framework.services.NrgEventService;
import org.nrg.xnatx.plugins.transfer.event.BatchTransferEvent;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferHistoryService;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferItemService;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferItemService.StartResult;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferProgressService.Completion;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferProgressService.Outcome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests what {@link BatchTransferMonitor} does with the item service's answers: whether an item runs, and the
 * batch's single terminal event. The item lifecycle itself is tested against PostgreSQL in
 * {@code BatchTransferItemServiceDbTest}.
 */
public class BatchTransferMonitorTest {

    private static final String  TRACKING_ID = "batch_transfer_123";
    private static final String  ITEM_ID     = "XNAT_E00001";
    private static final Integer USER_ID     = 42;

    private NrgEventService             eventService;
    private BatchTransferHistoryService historyService;
    private BatchTransferItemService    itemService;
    private BatchTransferMonitor        monitor;

    @Before
    public void setUp() {
        eventService   = mock(NrgEventService.class);
        historyService = mock(BatchTransferHistoryService.class);
        itemService    = mock(BatchTransferItemService.class);
        monitor        = new BatchTransferMonitor(eventService, historyService, itemService);
    }

    @Test
    public void register_registersTheRun() {
        monitor.register(TRACKING_ID, USER_ID, 10);
        verify(historyService).registerRun(TRACKING_ID, USER_ID, 10);
    }

    @Test
    public void startItem_returnsWhetherTheItemRuns() {
        when(itemService.start(TRACKING_ID, ITEM_ID)).thenReturn(new StartResult(true, null));
        assertTrue(monitor.startItem(TRACKING_ID, ITEM_ID));

        when(itemService.start(TRACKING_ID, ITEM_ID)).thenReturn(new StartResult(false, null));
        assertFalse(monitor.startItem(TRACKING_ID, ITEM_ID));
        verifyNoInteractions(eventService);
    }

    @Test
    public void startItem_thatStopsTheLastItem_firesTheStoppedEvent() {
        when(itemService.start(TRACKING_ID, ITEM_ID))
                .thenReturn(new StartResult(false, new Completion(USER_ID, 10, 1, 4)));

        assertFalse(monitor.startItem(TRACKING_ID, ITEM_ID));

        final BatchTransferEvent event = onlyEvent();
        assertEquals(BatchTransferEvent.Status.Warning, event.getStatus());
        assertEquals("Transfer stopped: 5 done, 1 failed, 4 stopped", event.getMessage());
        assertEquals(USER_ID, event.getUserId());
    }

    @Test
    public void startItem_whenTheDatabaseFails_runsTheItem() {
        when(itemService.start(TRACKING_ID, ITEM_ID)).thenThrow(new IllegalStateException("database down"));
        assertTrue(monitor.startItem(TRACKING_ID, ITEM_ID));
    }

    @Test
    public void finishItem_notCompleting_firesNothing() {
        when(itemService.finish(TRACKING_ID, ITEM_ID, Outcome.DONE, null, "XNAT_E00002")).thenReturn(null);

        monitor.finishItem(TRACKING_ID, ITEM_ID, Outcome.DONE, null, "XNAT_E00002");

        verifyNoInteractions(eventService);
    }

    @Test
    public void finishItem_completing_noFailures_firesCompleted() {
        when(itemService.finish(TRACKING_ID, ITEM_ID, Outcome.DONE, null, null))
                .thenReturn(new Completion(USER_ID, 3, 0, 0));

        monitor.finishItem(TRACKING_ID, ITEM_ID, Outcome.DONE, null, null);

        final BatchTransferEvent event = onlyEvent();
        assertEquals(BatchTransferEvent.Status.Completed, event.getStatus());
        assertEquals(TRACKING_ID, event.getTrackingId());
        assertEquals(USER_ID, event.getUserId());
    }

    @Test
    public void finishItem_completing_withFailures_firesWarning() {
        when(itemService.finish(TRACKING_ID, ITEM_ID, Outcome.FAILED, "boom", null))
                .thenReturn(new Completion(USER_ID, 3, 2, 0));

        monitor.finishItem(TRACKING_ID, ITEM_ID, Outcome.FAILED, "boom", null);

        final BatchTransferEvent event = onlyEvent();
        assertEquals(BatchTransferEvent.Status.Warning, event.getStatus());
        assertEquals("Transfer Complete with 2 warnings. Please review this log carefully.", event.getMessage());
    }

    @Test
    public void finishItem_whenTheDatabaseFails_doesNotThrow() {
        when(itemService.finish(TRACKING_ID, ITEM_ID, Outcome.DONE, null, null))
                .thenThrow(new IllegalStateException("database down"));
        monitor.finishItem(TRACKING_ID, ITEM_ID, Outcome.DONE, null, null);
        verifyNoInteractions(eventService);
    }

    private BatchTransferEvent onlyEvent() {
        final ArgumentCaptor<BatchTransferEvent> captor = ArgumentCaptor.forClass(BatchTransferEvent.class);
        verify(eventService).triggerEvent(captor.capture());
        return captor.getValue();
    }
}
