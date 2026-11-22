package com.edupulse.retrievalpost.rerank;

import java.util.List;

/**
 * Outcome of a rerank pass.
 *
 * @param results        top-K chunks in final order
 * @param reranked       {@code true} when Cohere produced the order, {@code false} on vector-order fallback
 * @param fallbackReason why reranking fell back; {@code null} when reranked or when there were no candidates
 * @param model          Cohere model used (or configured) for reranking
 * @param latencyMs      time spent in the rerank step, including fallback handling
 */
public record RerankResult(
        List<RankedChunk> results,
        boolean reranked,
        RerankFallbackReason fallbackReason,
        String model,
        long latencyMs
) {
}
