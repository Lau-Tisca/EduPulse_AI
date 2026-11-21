package com.edupulse.retrievalpost.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized configuration for post-retrieval (Story 4) mapped from APP_* environment variables.
 */
@ConfigurationProperties(prefix = "app")
public record RetrievalPostProperties(
        Rerank rerank,
        int tokenBudget,
        double similarityThreshold,
        Eval eval
) {
    public RetrievalPostProperties {
        if (rerank == null) {
            rerank = new Rerank(0, 0, null, null, 0);
        }
        if (tokenBudget <= 0) {
            tokenBudget = 2500;
        }
        if (similarityThreshold <= 0 || similarityThreshold > 1) {
            similarityThreshold = 0.65;
        }
        if (eval == null) {
            eval = new Eval(null);
        }
    }

    /**
     * Cohere cross-encoder reranking settings.
     *
     * @param topN         candidates sent to the reranker
     * @param topK         results kept after reranking
     * @param cohereApiKey optional; when blank the reranker falls back to vector order
     * @param model        pinned Cohere rerank model version
     * @param timeoutMs    connect/read timeout for the Cohere API call
     */
    public record Rerank(
            int topN,
            int topK,
            String cohereApiKey,
            String model,
            int timeoutMs
    ) {
        public Rerank {
            if (topN <= 0) {
                topN = 20;
            }
            if (topK <= 0) {
                topK = 5;
            }
            if (topK > topN) {
                topK = topN;
            }
            cohereApiKey = cohereApiKey == null ? "" : cohereApiKey.trim();
            if (model == null || model.isBlank()) {
                model = "rerank-v3.5";
            }
            if (timeoutMs <= 0) {
                timeoutMs = 3000;
            }
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
