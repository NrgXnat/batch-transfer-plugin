package org.nrg.xnatx.plugins.transfer.jms.requests;

import lombok.Data;

import java.io.Serializable;

/**
 * One queued Reimport item. The producer ({@code BatchTransferServiceImpl.enqueueReimport}) sends one
 * of these per selected image session to the {@value #DESTINATION} queue; {@code TransferReimportListener}
 * consumes them concurrently (the listener-container concurrency is the parallelism).
 *
 * <p>Plain {@link Serializable} POJO — it travels over JMS, so it carries no {@code UserI} (the
 * consumer reloads the user from {@link #username}) and no workflow handle: Reimport's session
 * workflow is created and completed inside {@code importExperiment}, and batch completion is tracked
 * by {@code BatchTransferMonitor}'s in-memory counter rather than per-message workflow ids.
 *
 * <p>The bean name {@value #DESTINATION} equals {@code uncapitalize("TransferItemRequest")}, so
 * {@code XDAT.sendJmsRequest(request)} resolves this queue by the request class's simple name.
 */
@Data
public class TransferItemRequest implements Serializable {

    public static final String DESTINATION = "transferItemRequest";

    private static final long serialVersionUID = 1L;

    private final String  trackingId;
    private final String  itemId;
    private final String  destinationProject;
    private final String  username;
    private final Integer requestingUserId;
    private final Boolean preserveSubjectLabel;
    private final Boolean preserveSessionLabel;
    private final String  anonScript;
}
