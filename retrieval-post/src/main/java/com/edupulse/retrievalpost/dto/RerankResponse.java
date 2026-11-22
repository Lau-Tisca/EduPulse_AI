package com.edupulse.retrievalpost.dto;

import com.edupulse.retrievalpost.pipeline.PipelineResult;
import com.edupulse.retrievalpost.rerank.RankedChunk;
import com.edupulse.retrievalpost.rerank.RerankFallbackReason;
import com.edupulse.retrievalpost.rerank.RerankResult;
import java.util.List;

/**
 * Response of {@code POST /rerank}: top-K chunks with citation metadata and both scores.
 *
 * @param candidates number of vector candidates considered before reranking
 */
public record RerankResponse(
        String query,
        boolean reranked,
        RerankFallbackReason fallbackReason,
        String model,
        int candidates,
        long latencyMs,
        List<RankedChunk> results
) {

    public static RerankResponse of(PipelineResult pipeline) {
        RerankResult result = pipeline.rerank();
        return new RerankResponse(
                pipeline.query(),
                result.reranked(),
                result.fallbackReason(),
                result.model(),
                pipeline.candidates(),
                result.latencyMs(),
                result.results());
    }
}
