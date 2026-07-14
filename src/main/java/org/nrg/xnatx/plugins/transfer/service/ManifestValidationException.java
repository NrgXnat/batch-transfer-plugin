package org.nrg.xnatx.plugins.transfer.service;

import org.springframework.http.HttpStatus;

/**
 * Thrown by {@link ManifestService} when a manifest can't be parsed at all (malformed CSV → 400) or exceeds
 * the configured row cap (→ 413). Carries the {@link HttpStatus} the API should return. A <i>parsed</i>
 * manifest that is merely incomplete (missing required columns, not-found rows) is <b>not</b> an error — it
 * comes back as a 200 result the UI renders.
 */
public class ManifestValidationException extends Exception {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;

    public ManifestValidationException(final HttpStatus status, final String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
