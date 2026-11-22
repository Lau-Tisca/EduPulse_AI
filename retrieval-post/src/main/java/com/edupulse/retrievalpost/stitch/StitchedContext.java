package com.edupulse.retrievalpost.stitch;

import java.util.List;

/**
 * Context assembled for generation, bounded by {@code APP_TOKEN_BUDGET}.
 *
 * @param passages           ordered by best rerank rank
 * @param totalTokens        sum of passage token counts (always {@code <= tokenBudget})
 * @param droppedHits        rerank hits that did not fit in the budget on their own
 * @param neighborsAvailable {@code false} when neighbor lookup failed and only the hits were used
 */
public record StitchedContext(
        List<StitchedPassage> passages,
        int totalTokens,
        int tokenBudget,
        int droppedHits,
        boolean neighborsAvailable
) {
}
