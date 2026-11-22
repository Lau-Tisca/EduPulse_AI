package com.edupulse.retrievalpost.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RetrievalPostPropertiesTest {

    private static RetrievalPostProperties.Rerank rerank(int topN, int topK, int timeoutMs) {
        return new RetrievalPostProperties.Rerank(topN, topK, " key ", "rerank-v3.5", timeoutMs);
    }

    @Test
    void keepsValidValuesIncludingZeroThresholdThatDisablesTheGate() {
        RetrievalPostProperties properties = new RetrievalPostProperties(rerank(20, 5, 1500), 3000, 0.0, null);

        assertThat(properties.similarityThreshold()).isZero();
        assertThat(properties.tokenBudget()).isEqualTo(3000);
        assertThat(properties.rerank().topN()).isEqualTo(20);
        assertThat(properties.rerank().topK()).isEqualTo(5);
        assertThat(properties.rerank().timeoutMs()).isEqualTo(1500);
        assertThat(properties.rerank().cohereApiKey()).isEqualTo("key");
        assertThat(properties.eval().goldenSetResource()).isEqualTo("classpath:eval/golden-set.json");
    }

    @Test
    void replacesOutOfRangeValuesWithDefaults() {
        RetrievalPostProperties properties = new RetrievalPostProperties(rerank(-1, 0, 0), -5, 33, null);

        assertThat(properties.similarityThreshold()).isEqualTo(RetrievalPostProperties.DEFAULT_SIMILARITY_THRESHOLD);
        assertThat(properties.tokenBudget()).isEqualTo(RetrievalPostProperties.DEFAULT_TOKEN_BUDGET);
        assertThat(properties.rerank().topN()).isEqualTo(20);
        assertThat(properties.rerank().topK()).isEqualTo(5);
        assertThat(properties.rerank().timeoutMs()).isEqualTo(3000);
    }

    @Test
    void clampsTopKToTopN() {
        assertThat(rerank(3, 5, 3000).topK()).isEqualTo(3);
    }

    @Test
    void neverPrintsTheApiKey() {
        assertThat(rerank(20, 5, 3000).toString()).doesNotContain("=key").contains("cohereApiKey=***");
        assertThat(new RetrievalPostProperties(null, 2500, 0.65, null).rerank().toString())
                .contains("cohereApiKey=<empty>");
    }
}
