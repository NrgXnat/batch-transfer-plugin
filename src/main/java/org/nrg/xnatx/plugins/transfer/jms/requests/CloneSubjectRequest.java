package org.nrg.xnatx.plugins.transfer.jms.requests;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * One subject's worth of Clone work. The producer ({@code BatchTransferServiceImpl.enqueueClone})
 * groups a Clone batch's selected items by subject and sends one of these per subject to the
 * {@value #DESTINATION} queue. {@code CloneSubjectListener} processes a subject's items serially in a
 * single consumer, so the destination subject/experiment is created once and reused — avoiding the
 * get-or-create race and shared-source mutation that make concurrent Clone of items under one subject
 * unsafe. Parallelism is across subjects (different subjects → different bundles → different consumers).
 *
 * <p>Plain {@link Serializable} POJO (travels over JMS): no {@code UserI} (reloaded from {@link #username}).
 * Bean name {@value #DESTINATION} equals {@code uncapitalize("CloneSubjectRequest")} for
 * {@code XDAT.sendJmsRequest} routing.
 */
@Data
public class CloneSubjectRequest implements Serializable {

    public static final String DESTINATION = "cloneSubjectRequest";

    private static final long serialVersionUID = 1L;

    private final String          trackingId;
    private final String          subjectId;
    private final List<CloneItem> items;
    private final String          username;
    private final Integer         requestingUserId;

    /** One selected item under the subject, paired with its destination project. */
    @Data
    public static class CloneItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String itemId;
        private final String destinationProject;
    }
}
