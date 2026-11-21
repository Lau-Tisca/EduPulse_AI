package com.edupulse.retrievalpost.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.edupulse.retrievalpost.config.RetrievalPostProperties;
import com.edupulse.retrievalpost.exception.CandidateRetrievalException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

class CandidateRetrieverTest {

    private VectorStore vectorStore;
    private CandidateRetriever retriever;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        RetrievalPostProperties properties = new RetrievalPostProperties(
                new RetrievalPostProperties.Rerank(20, 5, "", "rerank-v3.5", 3000), 2500, 0.65, null);
        retriever = new CandidateRetriever(vectorStore, properties);
    }

    @Test
    void searchesTopTwentyLatestChunksForQuery() {
        List<Document> found = List.of(
                Document.builder().id("a").text("pod").score(0.82).build(),
                Document.builder().id("b").text("node").score(0.74).build());
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(found);

        List<Document> candidates = retriever.retrieve("What is a Pod?");

        assertThat(candidates).isEqualTo(found);
        ArgumentCaptor<SearchRequest> request = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(request.capture());
        assertThat(request.getValue().getQuery()).isEqualTo("What is a Pod?");
        assertThat(request.getValue().getTopK()).isEqualTo(20);
        assertThat(request.getValue().getSimilarityThreshold()).isEqualTo(SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL);
        assertThat(request.getValue().getFilterExpression())
                .isEqualTo(new FilterExpressionBuilder().eq("is_latest", true).build());
    }

    @Test
    void returnsEmptyListWhenVectorStoreReturnsNull() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(null);

        assertThat(retriever.retrieve("query")).isEmpty();
    }

    @Test
    void wrapsVectorStoreFailure() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new IllegalStateException("embedding API unavailable"));

        assertThatThrownBy(() -> retriever.retrieve("query"))
                .isInstanceOf(CandidateRetrievalException.class)
                .hasMessageContaining("embedding API unavailable");
    }
}
