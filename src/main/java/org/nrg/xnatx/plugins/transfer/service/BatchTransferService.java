package org.nrg.xnatx.plugins.transfer.service;

import org.nrg.xnatx.plugins.transfer.model.BatchTransfer;
import org.nrg.xft.security.UserI;

public interface BatchTransferService {
    void submitTransferRequest(BatchTransfer batchTransferRequest, UserI user);
}
