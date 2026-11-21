package com.edupulse.retrievalpost.rerank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.edupulse.retrievalpost.config.RetrievalPostProperties;
import com.edupulse.retrievalpost.rerank.CohereRerankClient.RerankHit;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;

class RerankServiceTest {

    private CohereRerankClient cohereClient;
    private RerankService rerankService;

    @BeforeEach
    void setUp() {
        cohereClient = mock(CohereRerankClient.class);
        RetrievalPostProperties properties = new RetrievalPostProperties(
                new RetrievalPostProperties.Rerank(20, 5, "test-key", "rerank-v3.5", 3000), 2500, 0.65, null);
        rerankService = new RerankService(cohereClient, properties);
    }

    @Test
    void reordersCandidatesByCohereRelevanceAndKeepsTopFive() {
        List<Document> candidates = candidates(20);
        when(cohereClient.rerank(eq("What is RBAC?"), anyList(), eq(5))).thenReturn(List.of(
                new RerankHit(12, 0.95),
                new RerankHit(3, 0.88),
                new RerankHit(0, 0.71),
                new RerankHit(19, 0.60),
                new RerankHit(7, 0.33)));

        RerankResult result = rerankService.rerank("What is RBAC?", candidates);

        assertThat(result.reranked()).isTrue();
        assertThat(result.fallbackReason()).isNull();
        assertThat(result.model()).isEqualTo("rerank-v3.5");
        assertThat(result.results()).extracting(RankedChunk::chunkIndex).containsExactly(12, 3, 0, 19, 7);
        assertThat(result.results()).extracting(RankedChunk::rank).containsExactly(1, 2, 3, 4, 5);

        RankedChunk top = result.results().getFirst();
        assertThat(top.rerankScore()).isEqualTo(0.95);
        assertThat(top.vectorScore()).isEqualTo(scoreFor(12));
        assertThat(top.docId()).isEqualTo("k8s-doc");
        assertThat(top.source()).isEqualTo("k8s-doc.pdf");
        assertThat(top.version()).isEqualTo(1);
        assertThat(top.page()).isEqualTo(7);
        assertThat(top.content()).isEqualTo("chunk text 12");
    }

    @Test
    void sendsOnlyTopTwentyCandidatesToCohere() {
        when(cohereClient.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of(new RerankHit(0, 0.9)));

        rerankService.rerank("query", candidates(25));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> texts = ArgumentCaptor.forClass(List.class);
        verify(cohereClient).rerank(eq("query"), texts.capture(), eq(5));
        assertThat(texts.getValue()).hasSize(20).first().isEqualTo("chunk text 0");
        assertThat(texts.getValue()).last().isEqualTo("chunk text 19");
    }

    @Test
    void fallsBackToVectorOrderWhenApiKeyIsMissing() {
        when(cohereClient.rerank(anyString(), anyList(), anyInt()))
                .thenThrow(new CohereRerankException(RerankFallbackReason.MISSING_API_KEY, "no key"));

        RerankResult result = rerankService.rerank("query", candidates(20));

        assertFallbackToVectorOrder(result, RerankFallbackReason.MISSING_API_KEY);
    }

    @Test
    void fallsBackToVectorOrderOnTimeout() {
        when(cohereClient.rerank(anyString(), anyList(), anyInt()))
                .thenThrow(new CohereRerankException(RerankFallbackReason.TIMEOUT, "Read timed out"));

        RerankResult result = rerankService.rerank("query", candidates(20));

        assertFallbackToVectorOrder(result, RerankFallbackReason.TIMEOUT);
    }

    @Test
    void fallsBackToVectorOrderOnUnexpectedError() {
        when(cohereClient.rerank(anyString(), anyList(), anyInt())).thenThrow(new IllegalStateException("boom"));

        RerankResult result = rerankService.rerank("query", candidates(20));

        assertFallbackToVectorOrder(result, RerankFallbackReason.API_ERROR);
    }

    @Test
    void returnsAllCandidatesWhenFewerThanTopK() {
        when(cohereClient.rerank(anyString(), anyList(), eq(3))).thenReturn(List.of(
                new RerankHit(2, 0.9), new RerankHit(0, 0.8), new RerankHit(1, 0.1)));

        RerankResult result = rerankService.rerank("query", candidates(3));

        assertThat(result.reranked()).isTrue();
        assertThat(result.results()).extracting(RankedChunk::chunkIndex).containsExactly(2, 0, 1);
    }

    @Test
    void returnsEmptyResultWithoutCallingCohereWhenNoCandidates() {
        RerankResult result = rerankService.rerank("query", List.of());

        assertThat(result.results()).isEmpty();
        assertThat(result.reranked()).isFalse();
        assertThat(result.fallbackReason()).isNull();
        verify(cohereClient, never()).rerank(anyString(), any(), anyInt());
    }

    @Test
    void parsesNumericMetadataStoredAsStrings() {
        Document document = Document.builder()
                .id("id-1")
                .text("text")
                .metadata(Map.of("doc_id", "d", "source", "d.pdf", "page", "4", "chunk_index", "9.0", "version", 2L))
                .score(0.7)
                .build();
        when(cohereClient.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of(new RerankHit(0, 0.9)));

        RankedChunk chunk = rerankService.rerank("query", List.of(document)).results().getFirst();

        assertThat(chunk.page()).isEqualTo(4);
        assertThat(chunk.chunkIndex()).isEqualTo(9);
        assertThat(chunk.version()).isEqualTo(2);
    }

    private static void assertFallbackToVectorOrder(RerankResult result, RerankFallbackReason reason) {
        assertThat(result.reranked()).isFalse();
        assertThat(result.fallbackReason()).isEqualTo(reason);
        assertThat(result.results()).extracting(RankedChunk::chunkIndex).containsExactly(0, 1, 2, 3, 4);
        assertThat(result.results()).extracting(RankedChunk::rerankScore).containsOnlyNulls();
        assertThat(result.results()).extracting(RankedChunk::vectorScore)
                .containsExactly(scoreFor(0), scoreFor(1), scoreFor(2), scoreFor(3), scoreFor(4));
    }

    /** Candidates in vector order: chunk i has descending similarity. */
    private static List<Document> candidates(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> Document.builder()
                        .id("id-" + i)
                        .text("chunk text " + i)
                        .metadata(Map.of(
                                "doc_id", "k8s-doc",
                                "source", "k8s-doc.pdf",
                                "version", 1,
                                "page", 7,
                                "chunk_index", i,
                                "is_latest", true))
                        .score(scoreFor(i))
                        .build())
                .toList();
    }

    private static double scoreFor(int i) {
        return 0.90 - i * 0.01;
    }
}
