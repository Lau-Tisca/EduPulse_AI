package com.edupulse.retrievalpost.rerank;

/**
 * Why reranking fell back to the original vector similarity order.
 */
public enum RerankFallbackReason {
    MISSING_API_KEY,
    TIMEOUT,
    API_ERROR,
    INVALID_RESPONSE
}
