package org.nrg.xnatx.plugins.transfer.jms.listeners;

import lombok.extern.slf4j.Slf4j;
import org.nrg.framework.services.NrgEventService;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.security.UserI;
import org.nrg.xnatx.plugins.transfer.event.BatchTransferEvent;
import org.nrg.xnatx.plugins.transfer.jms.config.BatchTransferJmsConfig;
import org.nrg.xnatx.plugins.transfer.jms.requests.CloneSubjectRequest;
import org.nrg.xnatx.plugins.transfer.jms.tasks.BatchTransferMonitor;
import org.nrg.xnatx.plugins.transfer.model.EventInfo;
import org.nrg.xnatx.plugins.transfer.model.TransferMode;
import org.nrg.xnatx.plugins.transfer.model.TransferRequest;
import org.nrg.xnatx.plugins.transfer.service.BatchTransferService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

/**
 * Consumes one subject's Clone bundle per message and processes its items <b>serially</b> in this one
 * consumer — so the destination subject/experiment is created once (by the first item that needs it)
 * and reused by the rest, with no cross-thread get-or-create race or shared-source mutation.
 * Parallelism is <b>across subjects</b>: the listener-container concurrency (from
 * {@code BatchTransferQueuePrefsBean}) is how many subjects clone at once.
 *
 * <p>Never rethrows (a thrown exception would trigger JMS redelivery and reprocess the bundle). Each
 * item reports its outcome to {@link BatchTransferMonitor} exactly once, so the batch's completion
 * count always advances even when some items fail.
 */
@Slf4j
@Component
public class CloneSubjectListener {

    private final BatchTransferService   service;
    private final UserManagementServiceI userManagementService;
    private final NrgEventService        eventService;
    private final BatchTransferMonitor   monitor;

    @Autowired
    public CloneSubjectListener(final BatchTransferService service,
                                final UserManagementServiceI userManagementService,
                                final NrgEventService eventService,
                                final BatchTransferMonitor monitor) {
        this.service = service;
        this.userManagementService = userManagementService;
        this.eventService = eventService;
        this.monitor = monitor;
    }

    @JmsListener(id = BatchTransferJmsConfig.CLONE_LISTENER_ID,
            containerFactory = BatchTransferJmsConfig.CLONE_LISTENER_FACTORY,
            destination = CloneSubjectRequest.DESTINATION)
    public void onRequest(final CloneSubjectRequest request) {
        final String trackingId = request.getTrackingId();

        final UserI user;
        try {
            user = userManagementService.getUser(request.getUsername());
        } catch (Exception e) {
            log.error("Clone subject {}: could not load user {}", request.getSubjectId(), request.getUsername(), e);
            // Report every item in the bundle so fan-in still completes.
            for (final CloneSubjectRequest.CloneItem item : request.getItems()) {
                emitFail(request, item.getItemId() + " failed. Cause: could not load user " + request.getUsername());
                monitor.itemDone(trackingId, true, 0L);
            }
            return;
        }

        // One consumer owns this subject; process its items in order so the destination subject/
        // experiment is created once and reused (no get-or-create race). A failed parent fails its
        // dependents item-by-item; siblings still proceed.
        for (final CloneSubjectRequest.CloneItem item : request.getItems()) {
            final long itemStartNanos = System.nanoTime(); // TIMING INSTRUMENTATION (delete this line to remove)
            boolean failed = false;
            try {
                eventService.triggerEvent(BatchTransferEvent.progress(request.getRequestingUserId(),
                        monitor.currentPercent(trackingId), trackingId,
                        "Cloning " + item.getItemId() + " to " + item.getDestinationProject()));
                service.processItem(new TransferRequest(item.getDestinationProject(), item.getItemId(), TransferMode.CLONE),
                        user, new EventInfo(trackingId, monitor.currentPercent(trackingId)));
            } catch (Exception e) {
                log.error("Clone {} failed", item.getItemId(), e);
                failed = true;
                emitFail(request, item.getItemId() + " failed. Cause: " + (e.getCause() == null ? e.getMessage() : e.getCause().getMessage()));
            } finally {
                // TIMING INSTRUMENTATION: per-item duration (delete this calc + the itemStartNanos line; pass 0L)
                final long itemMillis = (System.nanoTime() - itemStartNanos) / 1_000_000L;
                log.info("Clone {} took {} ms", item.getItemId(), itemMillis);
                monitor.itemDone(trackingId, failed, itemMillis);
            }
        }
    }

    private void emitFail(final CloneSubjectRequest request, final String message) {
        eventService.triggerEvent(BatchTransferEvent.fail(request.getRequestingUserId(),
                monitor.currentPercent(request.getTrackingId()), request.getTrackingId(), message));
    }
}
