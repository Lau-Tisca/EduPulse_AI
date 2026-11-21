package com.edupulse.retrievalpost.retrieval;

import com.edupulse.retrievalpost.config.RetrievalPostProperties;
import com.edupulse.retrievalpost.exception.CandidateRetrievalException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

/**
 * Fetches the top {@code APP_RERANK_TOP_N} reranking candidates from pgvector, restricted to the latest
 * document versions ({@code is_latest == true}), ordered by descending cosine similarity.
 * <p>
 * Interim source of candidates until {@code retrieval-pre} (multi-query + RRF) hands them over directly.
 */
@Component
public class CandidateRetriever {

    private static final Logger log = LoggerFactory.getLogger(CandidateRetriever.class);
    private static final Filter.Expression LATEST_ONLY = new FilterExpressionBuilder().eq("is_latest", true).build();

    private final VectorStore vectorStore;
    private final int topN;

    public CandidateRetriever(VectorStore vectorStore, RetrievalPostProperties properties) {
        this.vectorStore = vectorStore;
        this.topN = properties.rerank().topN();
    }

    public List<Document> retrieve(String query) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topN)
                .similarityThresholdAll()
                .filterExpression(LATEST_ONLY)
                .build();

        long startedAt = System.nanoTime();
        List<Document> candidates;
        try {
            candidates = vectorStore.similaritySearch(request);
        } catch (RuntimeException ex) {
            throw new CandidateRetrievalException("Vector search failed: " + ex.getMessage(), ex);
        }
        if (candidates == null) {
            candidates = List.of();
        }
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
        log.info("Vector candidates retrieved: topN={}, found={}, topScore={}, latencyMs={}",
                topN, candidates.size(), candidates.isEmpty() ? null : candidates.getFirst().getScore(), latencyMs);
        return candidates;
    }
}
