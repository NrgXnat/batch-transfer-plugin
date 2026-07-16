package org.nrg.xnatx.plugins.transfer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * JSON body for {@code POST /xapi/transfer/validate/script}. {@code anonScript} is the raw DicomEdit (DE6)
 * template; {@code manifestColumns} is optional — when supplied the verdict reports which {@code ${csv.*}}
 * placeholders are unbound against those column names (omit it for interactive/no-manifest validation).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude
@JsonNaming(PropertyNamingStrategy.LowerCaseWithUnderscoresStrategy.class)
public class ScriptValidationRequest {
    private String       anonScript;
    private List<String> manifestColumns;
}
