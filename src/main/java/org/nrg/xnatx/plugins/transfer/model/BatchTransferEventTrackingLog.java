package org.nrg.xnatx.plugins.transfer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.nrg.xnatx.plugins.transfer.event.BatchTransferEvent;
import javax.validation.constraints.NotNull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@JsonInclude
public class BatchTransferEventTrackingLog {
    private List<BatchTransferEventTrackingLog.MessageEntry> entryList = new ArrayList<>();

    public BatchTransferEventTrackingLog() {}

    public BatchTransferEventTrackingLog(List<BatchTransferEventTrackingLog.MessageEntry> entryList) {
        this.entryList = entryList;
    }

    public List<BatchTransferEventTrackingLog.MessageEntry> getEntryList() {
        return entryList;
    }

    public void setEntryList(List<BatchTransferEventTrackingLog.MessageEntry> entryList) {
        this.entryList = entryList;
    }

    public void addToEntryList(BatchTransferEventTrackingLog.MessageEntry entry) {
        this.entryList.add(entry);
    }

    public void sortEntryList(){
        Collections.sort(this.entryList);
    }

    @JsonInclude
    public static class MessageEntry implements Comparable<BatchTransferEventTrackingLog.MessageEntry> {
        private BatchTransferEvent.Status status;
        private long eventTime;
        @Nullable
        private String message;

        public MessageEntry() {}

        public MessageEntry(BatchTransferEvent.Status status, long eventTime, @Nullable String message) {
            this.status = status;
            this.eventTime = eventTime;
            this.message = message;
        }

        public BatchTransferEvent.Status getStatus() {
            return status;
        }

        public void setStatus(BatchTransferEvent.Status status) {
            this.status = status;
        }

        @Nullable
        public String getMessage() {
            return message;
        }

        public void setMessage(@Nullable String message) {
            this.message = message;
        }

        public long getEventTime() {
            return eventTime;
        }

        public void setEventTime(long eventTime) {
            this.eventTime = eventTime;
        }

        @Override
        public int compareTo(@NotNull BatchTransferEventTrackingLog.MessageEntry o) {
            return Long.compare(this.eventTime, o.eventTime);
        }
    }
}
