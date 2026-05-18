package org.nrg.xnatx.plugins.transfer.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class EventInfo {
    private final String trackingId;
    private final int    progress;
}
