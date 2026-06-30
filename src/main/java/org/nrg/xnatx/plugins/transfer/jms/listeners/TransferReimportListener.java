package org.nrg.xnatx.plugins.transfer.jms.listeners;

import lombok.extern.slf4j.Slf4j;
import org.nrg.framework.services.NrgEventService;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.security.UserI;
import org.nrg.xnatx.plugins.transfer.event.BatchTransferEvent;
import org.nrg.xnatx.plugins.transfer.jms.config.BatchTransferJmsConfig;
import org.nrg.xnatx.plugins.transfer.jms.requests.TransferItemRequest;
import org.nrg.xnatx.plugins.transfer.jms.tasks.BatchTransferMonitor;
import org.nrg.xnatx.plugins.transfer.model.EventInfo;
import org.nrg.xnatx.plugins.transfer.model.TransferMode;
import org.nrg.xnatx.plugins.transfer.model.TransferRequest;
import org.nrg.xnatx.plugins.transfer.service.BatchTransferService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

/**
 * Consumes one Reimport item per message; the listener-container's concurrency (from
 * {@code BatchTransferQueuePrefsBean}) is the parallelism. Reloads the requesting user and runs the
 * shared per-item transfer logic via {@link BatchTransferService#processItem} — which, for Reimport,
 * drives the session's own persistent workflow (created inside {@code importExperiment}) to its
 * terminal state. The listener keeps no workflow of its own; it just reports each item's outcome to
 * {@link BatchTransferMonitor}, whose counter fires the batch terminal event once every item is in.
 *
 * <p><b>Never rethrows</b> — a thrown exception would trigger JMS redelivery and reprocess the item.
 * Every path reports exactly one outcome to the monitor (in {@code finally}), so the batch's
 * completion count always advances and fan-in can't stall on a handled error.
 */
@Slf4j
@Component
public class TransferReimportListener {

    private final BatchTransferService   service;
    private final UserManagementServiceI userManagementService;
    private final NrgEventService        eventService;
    private final BatchTransferMonitor   monitor;

    @Autowired
    public TransferReimportListener(final BatchTransferService service,
                                    final UserManagementServiceI userManagementService,
                                    final NrgEventService eventService,
                                    final BatchTransferMonitor monitor) {
        this.service = service;
        this.userManagementService = userManagementService;
        this.eventService = eventService;
        this.monitor = monitor;
    }

    @JmsListener(id = BatchTransferJmsConfig.REIMPORT_LISTENER_ID,
            containerFactory = BatchTransferJmsConfig.REIMPORT_LISTENER_FACTORY,
            destination = TransferItemRequest.DESTINATION)
    public void onRequest(final TransferItemRequest request) {
        final String trackingId     = request.getTrackingId();
        final String itemId         = request.getItemId();

        final UserI user;
        try {
            user = userManagementService.getUser(request.getUsername());
        } catch (Exception e) {
            log.error("Reimport {}: could not load user {}", itemId, request.getUsername(), e);
            emitFail(request, "Could not load user " + request.getUsername());
            monitor.itemDone(trackingId, true);
            return;
        }

        boolean failed = false;
        try {
            eventService.triggerEvent(BatchTransferEvent.progress(request.getRequestingUserId(),
                    monitor.currentPercent(trackingId), trackingId, "Reimporting " + itemId + " to " + request.getDestinationProject()));
            service.processItem(new TransferRequest(request.getDestinationProject(), itemId, TransferMode.REIMPORT,
                            request.getPreserveSubjectLabel(), request.getPreserveSessionLabel()),
                    user, new EventInfo(trackingId, monitor.currentPercent(trackingId)));
        } catch (Exception e) {
            log.error("Reimport {} failed", itemId, e);
            failed = true;
            emitFail(request, itemId + " failed. Cause: " + (e.getCause() == null ? e.getMessage() : e.getCause().getMessage()));
        } finally {
            monitor.itemDone(trackingId, failed);
        }
    }

    private void emitFail(final TransferItemRequest request, final String message) {
        eventService.triggerEvent(BatchTransferEvent.fail(request.getRequestingUserId(),
                monitor.currentPercent(request.getTrackingId()), request.getTrackingId(), message));
    }
}
