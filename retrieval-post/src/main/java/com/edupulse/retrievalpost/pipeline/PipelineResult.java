package com.edupulse.retrievalpost.pipeline;

import com.edupulse.retrievalpost.rerank.RerankResult;
import com.edupulse.retrievalpost.stitch.StitchedContext;

/**
 * Output of the post-retrieval pipeline for one query.
 *
 * @param candidates number of vector candidates considered before reranking
 * @param context    stitched context; {@code null} when only reranking was requested
 */
public record PipelineResult(
        String query,
        int candidates,
        RerankResult rerank,
        StitchedContext context
) {
}
