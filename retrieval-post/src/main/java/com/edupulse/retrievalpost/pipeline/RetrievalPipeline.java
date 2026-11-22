package com.edupulse.retrievalpost.pipeline;

import com.edupulse.retrievalpost.rerank.RerankResult;
import com.edupulse.retrievalpost.rerank.RerankService;
import com.edupulse.retrievalpost.retrieval.CandidateRetriever;
import com.edupulse.retrievalpost.stitch.NeighborStitchingService;
import com.edupulse.retrievalpost.stitch.StitchedContext;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

/**
 * Single place that orchestrates post-retrieval: candidates → Cohere rerank → (optionally) neighbor stitching.
 * Controllers stay thin; when {@code retrieval-pre} starts handing over fused candidates, only the candidate
 * source changes here.
 */
@Service
public class RetrievalPipeline {

    private static final Logger log = LoggerFactory.getLogger(RetrievalPipeline.class);

    private final CandidateRetriever candidateRetriever;
    private final RerankService rerankService;
    private final NeighborStitchingService stitchingService;

    public RetrievalPipeline(CandidateRetriever candidateRetriever,
                             RerankService rerankService,
                             NeighborStitchingService stitchingService) {
        this.candidateRetriever = candidateRetriever;
        this.rerankService = rerankService;
        this.stitchingService = stitchingService;
    }

    /** Vector search + rerank (Task 1). */
    public PipelineResult rerank(String query) {
        List<Document> candidates = candidateRetriever.retrieve(query);
        RerankResult rerank = rerankService.rerank(query, candidates);
        return new PipelineResult(query, candidates.size(), rerank, null);
    }

    /** Vector search + rerank + neighbor stitching within the token budget (Tasks 1–2). */
    public PipelineResult context(String query) {
        long startedAt = System.nanoTime();
        PipelineResult reranked = rerank(query);
        StitchedContext context = stitchingService.stitch(reranked.rerank().results());
        log.info("Context pipeline completed: candidates={}, reranked={}, passages={}, totalTokens={}, latencyMs={}",
                reranked.candidates(), reranked.rerank().reranked(), context.passages().size(),
                context.totalTokens(), (System.nanoTime() - startedAt) / 1_000_000);
        return new PipelineResult(query, reranked.candidates(), reranked.rerank(), context);
    }
}
