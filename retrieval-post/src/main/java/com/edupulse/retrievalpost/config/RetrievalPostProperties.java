package com.edupulse.retrievalpost.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized configuration for post-retrieval (Story 4) mapped from APP_* environment variables.
 * Invalid values are replaced by the agreed defaults and logged as {@code WARN}, never silently.
 */
@ConfigurationProperties(prefix = "app")
public record RetrievalPostProperties(
        Rerank rerank,
        int tokenBudget,
        double similarityThreshold,
        Eval eval
) {

    private static final Logger log = LoggerFactory.getLogger(RetrievalPostProperties.class);

    public static final int DEFAULT_TOKEN_BUDGET = 2500;
    public static final double DEFAULT_SIMILARITY_THRESHOLD = 0.65;

    public RetrievalPostProperties {
        if (rerank == null) {
            rerank = Rerank.defaults();
        }
        if (tokenBudget <= 0) {
            tokenBudget = replaced("APP_TOKEN_BUDGET", tokenBudget, DEFAULT_TOKEN_BUDGET);
        }
        // 0 is valid: it disables the "I don't know." gate (useful while calibrating).
        if (Double.isNaN(similarityThreshold) || similarityThreshold < 0 || similarityThreshold > 1) {
            similarityThreshold = replaced("APP_SIMILARITY_THRESHOLD (expected 0..1)",
                    similarityThreshold, DEFAULT_SIMILARITY_THRESHOLD);
        }
        if (eval == null) {
            eval = new Eval(null);
        }
    }

    private static <T> T replaced(String name, Object invalid, T fallback) {
        log.warn("Invalid {}={}; using default {}", name, invalid, fallback);
        return fallback;
    }

    /**
     * Cohere cross-encoder reranking settings.
     *
     * @param topN         candidates sent to the reranker
     * @param topK         results kept after reranking
     * @param cohereApiKey optional; when blank the reranker falls back to vector order
     * @param model        pinned Cohere rerank model version
     * @param timeoutMs    connect timeout and per-read socket timeout for the Cohere API call
     */
    public record Rerank(
            int topN,
            int topK,
            String cohereApiKey,
            String model,
            int timeoutMs
    ) {
        public static final int DEFAULT_TOP_N = 20;
        public static final int DEFAULT_TOP_K = 5;
        public static final String DEFAULT_MODEL = "rerank-v3.5";
        public static final int DEFAULT_TIMEOUT_MS = 3000;

        public Rerank {
            if (topN <= 0) {
                topN = replaced("APP_RERANK_TOP_N", topN, DEFAULT_TOP_N);
            }
            if (topK <= 0) {
                topK = replaced("APP_RERANK_TOP_K", topK, DEFAULT_TOP_K);
            }
            if (topK > topN) {
                topK = replaced("APP_RERANK_TOP_K (must be <= APP_RERANK_TOP_N=" + topN + ")", topK, topN);
            }
            cohereApiKey = cohereApiKey == null ? "" : cohereApiKey.trim();
            if (model == null || model.isBlank()) {
                model = DEFAULT_MODEL;
            }
            if (timeoutMs <= 0) {
                timeoutMs = replaced("APP_RERANK_TIMEOUT_MS", timeoutMs, DEFAULT_TIMEOUT_MS);
            }
        }

        static Rerank defaults() {
            return new Rerank(DEFAULT_TOP_N, DEFAULT_TOP_K, "", DEFAULT_MODEL, DEFAULT_TIMEOUT_MS);
        }

        public boolean hasApiKey() {
            return !cohereApiKey.isEmpty();
        }

        @Override
        public String toString() {
            return "Rerank[topN=" + topN + ", topK=" + topK + ", model=" + model
                    + ", timeoutMs=" + timeoutMs + ", cohereApiKey=" + (hasApiKey() ? "***" : "<empty>") + "]";
        }
    }

    public record Eval(String goldenSetResource) {
        public Eval {
            if (goldenSetResource == null || goldenSetResource.isBlank()) {
                goldenSetResource = "classpath:eval/golden-set.json";
            }
        }
    }
}
