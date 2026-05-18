package org.nrg.xnatx.plugins.transfer.event;

import org.nrg.xnatx.plugins.transfer.model.BatchTransferEventTrackingLog;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import javax.validation.constraints.NotNull;
import javax.annotation.Nullable;
import org.nrg.xdat.XDAT;
import org.nrg.xnat.tracking.model.TrackableEvent;

import java.io.IOException;

@Data
@AllArgsConstructor
@Builder
@Slf4j
public class BatchTransferEvent implements TrackableEvent {
    public enum Status { Waiting, InProgress, Warning, Completed, Failed; }

    @NotNull
    @Override
    public String getTrackingId() {
        return trackingId;
    }

    @NotNull
    @Override
    public Integer getUserId() {
        return userId;
    }

    @Override
    public boolean isSuccess() {
        return Status.Completed == status;
    }

    @Override
    public boolean isCompleted() { return progress == 100; }

    @Nullable
    @Override
    public String getMessage() {
        return message;
    }

    @Override
    public String updateTrackingPayload(@Nullable String currentPayload) throws IOException {
        BatchTransferEventTrackingLog statusLog;
        if (currentPayload != null) {
            statusLog = XDAT.getSerializerService().getObjectMapper()
                    .readValue(currentPayload, BatchTransferEventTrackingLog.class);
        } else {
            statusLog = new BatchTransferEventTrackingLog();
        }
        statusLog.addToEntryList(new BatchTransferEventTrackingLog.MessageEntry(status, eventTime, message));
        statusLog.sortEntryList();
        return XDAT.getSerializerService().getObjectMapper().writeValueAsString(statusLog);
    }

    @Override
    public String toString() {
        return getTrackingId() + ": Batch Transfer : " + status.toString() + " (" + progress + "%)";
    }

    public static BatchTransferEvent progress(final Integer userId, final int progress, final String trackingId, final String message) {
        return builder().status(Status.InProgress)
                        .progress(progress)
                        .trackingId(trackingId)
                        .message(message)
                        .eventTime(System.currentTimeMillis())
                        .userId(userId)
                        .build();
    }

    public static BatchTransferEvent complete(final Integer userId, final String trackingId, final String message) {
        return builder().status(Status.Completed)
                        .progress(100)
                        .trackingId(trackingId)
                        .message(message)
                        .eventTime(System.currentTimeMillis())
                        .userId(userId)
                        .build();
    }

    public static BatchTransferEvent warn(final Integer userId, final int progress, final String trackingId, final String message) {
        return builder().status(Status.Warning)
                        .progress(progress)
                        .trackingId(trackingId)
                        .message(message)
                        .eventTime(System.currentTimeMillis())
                        .userId(userId)
                        .build();
    }

    public static BatchTransferEvent fail(final Integer userId, final int progress, final String trackingId, final String message) {
        return builder().status(Status.Failed)
                .progress(progress)
                .trackingId(trackingId)
                .message(message)
                .eventTime(System.currentTimeMillis())
                .userId(userId)
                .build();
    }

    public static BatchTransferEvent waiting(final Integer userId, final String trackingId, final String message) {
        return builder().status(Status.Waiting)
                .progress(0)
                .trackingId(trackingId)
                .message(message)
                .eventTime(System.currentTimeMillis())
                .userId(userId)
                .build();
    }

    private int           progress;
    private String        trackingId;
    private String        message;
    private long          eventTime;
    private Integer       userId;
    private Status        status;
}
