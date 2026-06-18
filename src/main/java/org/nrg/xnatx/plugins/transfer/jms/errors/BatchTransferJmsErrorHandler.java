package org.nrg.xnatx.plugins.transfer.jms.errors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.ErrorHandler;

/**
 * Error handler wired into the Batch Transfer JMS listener-container factories. A listener that
 * throws would otherwise have its exception swallowed by the container; here it is logged to
 * {@code batch-transfer.log}. The listeners themselves catch-and-convert (recording the per-item
 * workflow as failed and emitting a fail event) and never rethrow, so this is a backstop for
 * unexpected container-level errors rather than the normal per-item failure path.
 */
@Slf4j
public class BatchTransferJmsErrorHandler implements ErrorHandler {

    @Override
    public void handleError(final Throwable t) {
        log.error("Batch Transfer JMS listener error", t);
    }
}
