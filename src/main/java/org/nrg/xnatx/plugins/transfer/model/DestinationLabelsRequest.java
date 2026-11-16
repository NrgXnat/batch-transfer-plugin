package org.nrg.xnatx.plugins.transfer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * JSON body for {@code POST /xapi/transfer/validate/destination_labels}: the session labels a Reimport may give its
 * sessions in {@code destinationProject} (by ID or alias), to find the ones already taken there.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude
@JsonNaming(PropertyNamingStrategy.LowerCaseWithUnderscoresStrategy.class)
public class DestinationLabelsRequest {
    private String       destinationProject;
    private List<String> sessionLabels;
}
