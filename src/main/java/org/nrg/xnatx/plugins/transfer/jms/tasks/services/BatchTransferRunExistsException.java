package org.nrg.xnatx.plugins.transfer.jms.tasks.services;

/** A submit reused a tracking id that already has a run. */
public class BatchTransferRunExistsException extends RuntimeException {
    public BatchTransferRunExistsException(final String trackingId) {
        super("A transfer with tracking id " + trackingId + " already exists.");
    }
}
