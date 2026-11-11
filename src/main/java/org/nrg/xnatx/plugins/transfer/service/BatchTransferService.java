package org.nrg.xnatx.plugins.transfer.service;

import org.nrg.xnatx.plugins.transfer.model.BatchTransfer;
import org.nrg.xnatx.plugins.transfer.model.EventInfo;
import org.nrg.xnatx.plugins.transfer.model.TransferRequest;
import org.nrg.xft.security.UserI;

public interface BatchTransferService {
    void submitTransferRequest(BatchTransfer batchTransferRequest, UserI user);

    /**
     * Validates, loads, permission-checks, and performs a single transfer item by mode. Shared by
     * the sequential Share/Clone path and the JMS consumers (the parallel Reimport path), so it is
     * the single source of truth for per-item transfer logic. Throws on any failure. Returns the ID of what
     * the item became in the destination (the shared item, the clone, or the reimported session), or null.
     */
    String processItem(TransferRequest request, UserI user, EventInfo eventInfo) throws Exception;
}
