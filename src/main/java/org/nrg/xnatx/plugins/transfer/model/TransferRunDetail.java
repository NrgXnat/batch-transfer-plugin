package org.nrg.xnatx.plugins.transfer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.List;

/**
 * A run with its items, as {@code GET /xapi/transfer/history/{tracking_id}} returns it. {@code anonScript} is present
 * only for users allowed to see a run's script and values.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategy.LowerCaseWithUnderscoresStrategy.class)
public class TransferRunDetail {
    private TransferRun           run;
    private boolean               canStop;
    private String                anonScript;
    private List<TransferRunItem> items;
}
