package org.nrg.xnatx.plugins.transfer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response of {@code POST /xapi/transfer/validate/destination_labels}: the destination project's ID and, of the
 * labels asked about, those an experiment there already has (owned or shared in), sorted.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude
@JsonNaming(PropertyNamingStrategy.LowerCaseWithUnderscoresStrategy.class)
public class DestinationLabelsResult {
    private String       destinationProject;
    private List<String> existingSessionLabels;
}
