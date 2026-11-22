package com.edupulse.retrievalpost.dto;

import com.edupulse.retrievalpost.pipeline.PipelineResult;
import com.edupulse.retrievalpost.rerank.RerankFallbackReason;
import com.edupulse.retrievalpost.rerank.RerankResult;
import com.edupulse.retrievalpost.stitch.StitchedContext;
import com.edupulse.retrievalpost.stitch.StitchedPassage;
import java.util.List;

/**
 * Response of {@code POST /context}: reranking outcome plus the stitched, budget-bounded passages.
 */
public record ContextResponse(
        String query,
        boolean reranked,
        RerankFallbackReason fallbackReason,
        String model,
        int candidates,
        int tokenBudget,
        int totalTokens,
        int droppedHits,
        boolean neighborsAvailable,
        List<StitchedPassage> passages
) {

    public static ContextResponse of(PipelineResult pipeline) {
        RerankResult rerank = pipeline.rerank();
        StitchedContext context = pipeline.context();
        return new ContextResponse(
                pipeline.query(),
                rerank.reranked(),
                rerank.fallbackReason(),
                rerank.model(),
                pipeline.candidates(),
                context.tokenBudget(),
                context.totalTokens(),
                context.droppedHits(),
                context.neighborsAvailable(),
                context.passages());
    }
}
