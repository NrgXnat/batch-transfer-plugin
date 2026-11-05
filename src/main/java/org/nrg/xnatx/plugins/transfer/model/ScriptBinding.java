package org.nrg.xnatx.plugins.transfer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Whether every {@code ${csv.*}} placeholder in an anon-script template maps to an available manifest value
 * column. Present in a manifest-validation result only when an {@code anon_script} was supplied.
 * {@code unbound} lists the placeholder column names with no matching value column.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude
@JsonNaming(PropertyNamingStrategy.LowerCaseWithUnderscoresStrategy.class)
public class ScriptBinding {
    private boolean bound;
    private List<String> unbound;
}
