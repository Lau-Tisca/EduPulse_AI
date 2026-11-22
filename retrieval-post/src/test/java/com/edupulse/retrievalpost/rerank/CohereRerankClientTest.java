package com.edupulse.retrievalpost.rerank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.edupulse.retrievalpost.config.CohereClientConfig;
import com.edupulse.retrievalpost.config.RetrievalPostProperties;
import com.edupulse.retrievalpost.rerank.CohereRerankClient.RerankHit;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class CohereRerankClientTest {

    private static final String RERANK_URL = CohereClientConfig.COHERE_BASE_URL + "/v2/rerank";
    private static final List<String> DOCUMENTS = IntStream.range(0, 20)
            .mapToObj(i -> "chunk-" + i)
            .toList();

    private MockRestServiceServer server;

    private CohereRerankClient clientWithKey(String apiKey) {
        RestClient.Builder builder = RestClient.builder().baseUrl(CohereClientConfig.COHERE_BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        RetrievalPostProperties properties = new RetrievalPostProperties(
                new RetrievalPostProperties.Rerank(20, 5, apiKey, "rerank-v3.5", 3000), 2500, 0.65, null);
        return new CohereRerankClient(builder.build(), properties);
    }

    @Test
    void reranksTwentyCandidatesDownToFiveOrderedByRelevance() {
        CohereRerankClient client = clientWithKey("test-key");
        server.expect(requestTo(RERANK_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("rerank-v3.5"))
                .andExpect(jsonPath("$.query").value("How does RBAC work?"))
                .andExpect(jsonPath("$.top_n").value(5))
                .andExpect(jsonPath("$.documents.length()").value(20))
                .andRespond(withSuccess("""
                        {"id":"r1","results":[
                          {"index":7,"relevance_score":0.91},
                          {"index":2,"relevance_score":0.97},
                          {"index":15,"relevance_score":0.64},
                          {"index":0,"relevance_score":0.80},
                          {"index":11,"relevance_score":0.42}
                        ],"meta":{"api_version":{"version":"2"}}}
                        """, MediaType.APPLICATION_JSON));

        List<RerankHit> hits = client.rerank("How does RBAC work?", DOCUMENTS, 5);

        assertThat(hits).extracting(RerankHit::index).containsExactly(2, 7, 0, 15, 11);
        assertThat(hits.getFirst().relevanceScore()).isEqualTo(0.97);
        server.verify();
    }

    @Test
    void capsTopNToNumberOfDocuments() {
        CohereRerankClient client = clientWithKey("test-key");
        server.expect(requestTo(RERANK_URL))
                .andExpect(jsonPath("$.top_n").value(2))
                .andRespond(withSuccess("""
                        {"results":[{"index":1,"relevance_score":0.9},{"index":0,"relevance_score":0.5}]}
                        """, MediaType.APPLICATION_JSON));

        List<RerankHit> hits = client.rerank("query", List.of("a", "b"), 5);

        assertThat(hits).extracting(RerankHit::index).containsExactly(1, 0);
        server.verify();
    }

    @Test
    void failsWithMissingApiKeyWithoutCallingCohere() {
        CohereRerankClient client = clientWithKey("  ");

        assertThat(client.isConfigured()).isFalse();
        assertThatThrownBy(() -> client.rerank("query", DOCUMENTS, 5))
                .isInstanceOf(CohereRerankException.class)
                .extracting(ex -> ((CohereRerankException) ex).reason())
                .isEqualTo(RerankFallbackReason.MISSING_API_KEY);
        server.verify();
    }

    @Test
    void returnsEmptyListForNoDocumentsWithoutCallingCohere() {
        CohereRerankClient client = clientWithKey("test-key");

        assertThat(client.rerank("query", List.of(), 5)).isEmpty();
        server.verify();
    }

    @Test
    void mapsServerErrorToApiError() {
        CohereRerankClient client = clientWithKey("test-key");
        server.expect(requestTo(RERANK_URL)).andRespond(withServerError());

        assertFallbackReason(client, RerankFallbackReason.API_ERROR);
    }

    @Test
    void mapsRateLimitToApiError() {
        CohereRerankClient client = clientWithKey("test-key");
        server.expect(requestTo(RERANK_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertFallbackReason(client, RerankFallbackReason.API_ERROR);
    }

    @Test
    void mapsReadTimeoutToTimeout() {
        CohereRerankClient client = clientWithKey("test-key");
        server.expect(requestTo(RERANK_URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertFallbackReason(client, RerankFallbackReason.TIMEOUT);
    }

    @Test
    void rejectsOutOfRangeIndexAsInvalidResponse() {
        CohereRerankClient client = clientWithKey("test-key");
        server.expect(requestTo(RERANK_URL)).andRespond(withSuccess("""
                {"results":[{"index":42,"relevance_score":0.9}]}
                """, MediaType.APPLICATION_JSON));

        assertFallbackReason(client, RerankFallbackReason.INVALID_RESPONSE);
    }

    @Test
    void rejectsDuplicateIndexAsInvalidResponse() {
        CohereRerankClient client = clientWithKey("test-key");
        server.expect(requestTo(RERANK_URL)).andRespond(withSuccess("""
                {"results":[{"index":3,"relevance_score":0.9},{"index":3,"relevance_score":0.8}]}
                """, MediaType.APPLICATION_JSON));

        assertFallbackReason(client, RerankFallbackReason.INVALID_RESPONSE);
    }

    @Test
    void rejectsEmptyResultsAsInvalidResponse() {
        CohereRerankClient client = clientWithKey("test-key");
        server.expect(requestTo(RERANK_URL)).andRespond(withSuccess("""
                {"results":[]}
                """, MediaType.APPLICATION_JSON));

        assertFallbackReason(client, RerankFallbackReason.INVALID_RESPONSE);
    }

    private void assertFallbackReason(CohereRerankClient client, RerankFallbackReason expected) {
        assertThatThrownBy(() -> client.rerank("query", DOCUMENTS, 5))
                .isInstanceOf(CohereRerankException.class)
                .extracting(ex -> ((CohereRerankException) ex).reason())
                .isEqualTo(expected);
        server.verify();
    }
}
