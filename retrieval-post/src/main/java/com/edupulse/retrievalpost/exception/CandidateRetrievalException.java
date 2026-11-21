package com.edupulse.retrievalpost.exception;

/**
 * Vector search (embedding call or pgvector query) failed; mapped to {@code 503 Service Unavailable}.
 */
public class CandidateRetrievalException extends RuntimeException {

    public CandidateRetrievalException(String message, Throwable cause) {
        super(message, cause);
    }
}
