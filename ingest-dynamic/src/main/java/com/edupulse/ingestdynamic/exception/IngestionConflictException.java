package com.edupulse.ingestdynamic.exception;

/**
 * Thrown when an ingestion claim for a document is already held by another in-flight worker.
 * Mapped to HTTP 409 Conflict for REST callers and treated as retryable by the webhook queue.
 */
public class IngestionConflictException extends RuntimeException {

    public IngestionConflictException(String message) {
        super(message);
    }
}
