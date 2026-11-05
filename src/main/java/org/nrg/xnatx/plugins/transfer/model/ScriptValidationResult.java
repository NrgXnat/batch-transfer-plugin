package org.nrg.xnatx.plugins.transfer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Advisory verdict for {@code POST /xapi/transfer/validate/script}. Always returned with HTTP 200 — validity
 * is data, not a status code. {@code valid} is true only when there are no parse {@code errors}, no
 * {@code restrictionViolations}, and (when {@code binding} was computed) every placeholder is bound; it mirrors
 * what submit would enforce, so the UI can gate on this single flag. {@code binding} is present only when the
 * request supplied {@code manifest_columns}; {@code message} carries an advisory note (e.g. that a manifest is
 * needed to fully validate a templated script). Placeholders and {@code binding.unbound} are bare column names,
 * consistent with the manifest-validation path.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategy.LowerCaseWithUnderscoresStrategy.class)
public class ScriptValidationResult {
    private boolean       valid;
    private String        dialect;
    private int           sizeBytes;
    private List<String>  placeholders;
    private List<String>  errors;
    private List<String>  restrictionViolations;
    private ScriptBinding binding;
    private String        message;
}
