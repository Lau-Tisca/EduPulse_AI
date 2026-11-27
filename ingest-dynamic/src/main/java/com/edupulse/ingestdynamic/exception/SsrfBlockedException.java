package com.edupulse.ingestdynamic.exception;

/**
 * Thrown when an outbound URL violates SSRF rules (non-HTTPS, disallowed domain, or private/internal IP).
 */
public class SsrfBlockedException extends RuntimeException {

    public SsrfBlockedException(String message) {
        super(message);
    }
}
