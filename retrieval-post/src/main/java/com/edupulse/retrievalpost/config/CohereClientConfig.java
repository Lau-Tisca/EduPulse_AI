package com.edupulse.retrievalpost.config;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Dedicated {@link RestClient} for the Cohere API with bounded connect/read timeouts, so a slow
 * reranker can never block the retrieval request beyond {@code APP_RERANK_TIMEOUT_MS}.
 */
@Configuration
public class CohereClientConfig {

    public static final String COHERE_BASE_URL = "https://api.cohere.com";

    @Bean
    public RestClient cohereRestClient(RetrievalPostProperties properties) {
        Duration timeout = Duration.ofMillis(properties.rerank().timeoutMs());
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);

        return RestClient.builder()
                .baseUrl(COHERE_BASE_URL)
                .requestFactory(requestFactory)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
