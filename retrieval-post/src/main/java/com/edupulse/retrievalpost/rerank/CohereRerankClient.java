package com.edupulse.retrievalpost.rerank;

import com.edupulse.retrievalpost.config.RetrievalPostProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Thin client for the Cohere Rerank v2 API ({@code POST /v2/rerank}).
 * Translates every failure into a {@link CohereRerankException} with a {@link RerankFallbackReason}
 * so callers can fall back to vector order without inspecting HTTP details.
 */
@Component
public class CohereRerankClient {

    private static final Logger log = LoggerFactory.getLogger(CohereRerankClient.class);
    private static final String RERANK_PATH = "/v2/rerank";

    private final RestClient restClient;
    private final RetrievalPostProperties.Rerank settings;

    public CohereRerankClient(@Qualifier("cohereRestClient") RestClient restClient,
                              RetrievalPostProperties properties) {
        this.restClient = restClient;
        this.settings = properties.rerank();
    }

    public boolean isConfigured() {
        return settings.hasApiKey();
    }

    /**
     * Reranks {@code documents} against {@code query}.
     *
     * @return hits ordered by descending relevance, each pointing to an index in {@code documents}
     * @throws CohereRerankException when the key is missing, the call fails/times out, or the response is invalid
     */
    public List<RerankHit> rerank(String query, List<String> documents, int topN) {
        if (!isConfigured()) {
            throw new CohereRerankException(RerankFallbackReason.MISSING_API_KEY, "COHERE_API_KEY is not configured");
        }
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        int requestedTopN = Math.min(Math.max(topN, 1), documents.size());
        CohereRerankRequest request = new CohereRerankRequest(settings.model(), query, documents, requestedTopN);

        long startedAt = System.nanoTime();
        CohereRerankResponse response;
        try {
            response = restClient.post()
                    .uri(RERANK_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + settings.cohereApiKey())
                    .body(request)
                    .retrieve()
                    .body(CohereRerankResponse.class);
        } catch (RestClientResponseException ex) {
            throw new CohereRerankException(RerankFallbackReason.API_ERROR,
                    "Cohere rerank returned HTTP " + ex.getStatusCode().value(), ex);
        } catch (ResourceAccessException ex) {
            RerankFallbackReason reason = isTimeout(ex) ? RerankFallbackReason.TIMEOUT : RerankFallbackReason.API_ERROR;
            throw new CohereRerankException(reason, "Cohere rerank I/O failure: " + ex.getMessage(), ex);
        } catch (RestClientException ex) {
            throw new CohereRerankException(RerankFallbackReason.INVALID_RESPONSE,
                    "Cohere rerank response could not be read: " + ex.getMessage(), ex);
        }

        List<RerankHit> hits = validate(response, documents.size(), requestedTopN);
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
        log.info("Cohere rerank completed: model={}, documents={}, topN={}, results={}, latencyMs={}",
                settings.model(), documents.size(), requestedTopN, hits.size(), latencyMs);
        return hits;
    }

    private static List<RerankHit> validate(CohereRerankResponse response, int documentCount, int requestedTopN) {
        if (response == null || response.results() == null || response.results().isEmpty()) {
            throw new CohereRerankException(RerankFallbackReason.INVALID_RESPONSE, "Cohere rerank returned no results");
        }
        if (response.results().size() > requestedTopN) {
            throw new CohereRerankException(RerankFallbackReason.INVALID_RESPONSE,
                    "Cohere rerank returned " + response.results().size() + " results, expected at most " + requestedTopN);
        }
        Set<Integer> seen = new HashSet<>();
        for (CohereRerankResponse.Result result : response.results()) {
            if (result == null || result.index() == null || result.relevanceScore() == null) {
                throw new CohereRerankException(RerankFallbackReason.INVALID_RESPONSE,
                        "Cohere rerank result is missing index or relevance_score");
            }
            int index = result.index();
            if (index < 0 || index >= documentCount || !seen.add(index)) {
                throw new CohereRerankException(RerankFallbackReason.INVALID_RESPONSE,
                        "Cohere rerank returned invalid or duplicate index " + index);
            }
        }
        return response.results().stream()
                .map(result -> new RerankHit(result.index(), result.relevanceScore()))
                .sorted(Comparator.comparingDouble(RerankHit::relevanceScore).reversed())
                .toList();
    }

    private static boolean isTimeout(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /**
     * One reranked candidate: position in the submitted document list and Cohere relevance score (0..1).
     */
    public record RerankHit(int index, double relevanceScore) {
    }

    record CohereRerankRequest(
            String model,
            String query,
            List<String> documents,
            @JsonProperty("top_n") int topN
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CohereRerankResponse(List<Result> results) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Result(
                Integer index,
                @JsonProperty("relevance_score") Double relevanceScore
        ) {
        }
    }
}
