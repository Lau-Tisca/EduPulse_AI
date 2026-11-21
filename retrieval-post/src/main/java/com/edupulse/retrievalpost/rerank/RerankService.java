package com.edupulse.retrievalpost.rerank;

import com.edupulse.retrievalpost.config.RetrievalPostProperties;
import com.edupulse.retrievalpost.rerank.CohereRerankClient.RerankHit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

/**
 * Cross-encoder reranking (Story 4 — Task 1): sends the top {@code APP_RERANK_TOP_N} vector candidates to
 * Cohere and keeps the best {@code APP_RERANK_TOP_K}. Any failure (missing key, HTTP error, timeout,
 * invalid response) falls back to the original vector similarity order, so retrieval never fails because
 * of the reranker.
 */
@Service
public class RerankService {

    private static final Logger log = LoggerFactory.getLogger(RerankService.class);

    private final CohereRerankClient cohereClient;
    private final RetrievalPostProperties.Rerank settings;
    private final AtomicBoolean missingKeyWarned = new AtomicBoolean(false);

    public RerankService(CohereRerankClient cohereClient, RetrievalPostProperties properties) {
        this.cohereClient = cohereClient;
        this.settings = properties.rerank();
    }

    /**
     * @param query      the user query the candidates were retrieved for
     * @param candidates vector search results, ordered by descending similarity
     */
    public RerankResult rerank(String query, List<Document> candidates) {
        long startedAt = System.nanoTime();
        if (candidates == null || candidates.isEmpty()) {
            log.info("Rerank skipped: no candidates");
            return new RerankResult(List.of(), false, null, settings.model(), 0);
        }

        List<Document> pool = candidates.size() > settings.topN()
                ? candidates.subList(0, settings.topN())
                : candidates;
        int topK = Math.min(settings.topK(), pool.size());

        try {
            List<String> texts = pool.stream().map(RerankService::textOf).toList();
            List<RerankHit> hits = cohereClient.rerank(query, texts, topK);
            List<RankedChunk> ranked = new ArrayList<>(hits.size());
            for (RerankHit hit : hits) {
                ranked.add(RankedChunk.from(pool.get(hit.index()), ranked.size() + 1, hit.relevanceScore()));
            }
            long latencyMs = elapsedMs(startedAt);
            log.info("Rerank applied: model={}, candidates={}, kept={}, topScore={}, latencyMs={}",
                    settings.model(), pool.size(), ranked.size(),
                    ranked.isEmpty() ? null : ranked.getFirst().rerankScore(), latencyMs);
            return new RerankResult(List.copyOf(ranked), true, null, settings.model(), latencyMs);
        } catch (CohereRerankException ex) {
            logFallback(ex);
            return fallback(pool, topK, ex.reason(), startedAt);
        } catch (RuntimeException ex) {
            log.warn("Rerank fell back to vector order: reason={}, error={}",
                    RerankFallbackReason.API_ERROR, ex.toString());
            return fallback(pool, topK, RerankFallbackReason.API_ERROR, startedAt);
        }
    }

    private RerankResult fallback(List<Document> pool, int topK, RerankFallbackReason reason, long startedAt) {
        List<RankedChunk> ranked = new ArrayList<>(topK);
        for (int i = 0; i < topK; i++) {
            ranked.add(RankedChunk.from(pool.get(i), i + 1, null));
        }
        return new RerankResult(List.copyOf(ranked), false, reason, settings.model(), elapsedMs(startedAt));
    }

    private void logFallback(CohereRerankException ex) {
        if (ex.reason() == RerankFallbackReason.MISSING_API_KEY) {
            if (missingKeyWarned.compareAndSet(false, true)) {
                log.warn("COHERE_API_KEY is empty; reranking disabled, using vector similarity order");
            } else {
                log.debug("Rerank skipped: COHERE_API_KEY is empty");
            }
            return;
        }
        log.warn("Rerank fell back to vector order: reason={}, error={}", ex.reason(), ex.getMessage());
    }

    private static String textOf(Document document) {
        String text = document.getText();
        return text == null ? "" : text;
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
