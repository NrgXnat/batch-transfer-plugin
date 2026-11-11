package org.nrg.xnatx.plugins.transfer.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** One page of runs, newest first. {@code page} counts from 0. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategy.LowerCaseWithUnderscoresStrategy.class)
public class TransferRunPage {
    private List<TransferRun> runs;
    private long              total;
    private int               page;
    private int               size;
}
