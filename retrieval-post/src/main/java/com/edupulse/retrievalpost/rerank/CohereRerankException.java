package com.edupulse.retrievalpost.rerank;

/**
 * Raised when the Cohere Rerank API cannot produce a usable ranking; carries the fallback reason.
 */
public class CohereRerankException extends RuntimeException {

    private final RerankFallbackReason reason;

    public CohereRerankException(RerankFallbackReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public CohereRerankException(RerankFallbackReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public RerankFallbackReason reason() {
        return reason;
    }
}
