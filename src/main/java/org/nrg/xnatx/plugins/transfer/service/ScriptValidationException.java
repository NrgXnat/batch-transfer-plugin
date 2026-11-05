package org.nrg.xnatx.plugins.transfer.service;

import org.springframework.http.HttpStatus;

/**
 * Thrown by {@link ScriptCompiler} when a custom anonymization script (or its substitution values) fails a
 * submit-time check — bad size, unparsable DicomEdit, a restricted verb/version, an unbound {@code ${csv.*}}
 * placeholder, or a value outside the safe charset. Carries the {@link HttpStatus} the API should return so
 * the controller can translate it to a response without re-deciding the status.
 */
public class ScriptValidationException extends Exception {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;

    public ScriptValidationException(final HttpStatus status, final String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
