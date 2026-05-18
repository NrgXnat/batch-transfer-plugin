package org.nrg.xnatx.plugins.transfer.event.listeners;

import org.nrg.xnatx.plugins.transfer.event.BatchTransferEvent;
import lombok.extern.slf4j.Slf4j;
import org.nrg.xnat.event.EventListener;
import org.nrg.xnat.tracking.TrackEvent;
import reactor.bus.Event;
import reactor.fn.Consumer;

/**
 * Handles events fired as archive operations occur.
 */
@EventListener
@Slf4j
public class BatchTransferEventListener implements Consumer<Event<BatchTransferEvent>> {

    @Override
    @TrackEvent
    public void accept(Event<BatchTransferEvent> busEvent) {
        log.debug(busEvent.getData().getMessage());
        log.debug(busEvent.getData().toString());
    }
}
