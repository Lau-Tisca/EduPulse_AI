package com.edupulse.retrievalpost.config;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Dedicated {@link RestClient} for the Cohere API.
 * <p>
 * {@code APP_RERANK_TIMEOUT_MS} is applied as the connect timeout and as the per-read socket timeout. It is not a
 * deadline for the whole call: a server that keeps sending data slower than the read timeout can make a call last
 * longer. In practice a stalled or unreachable Cohere API fails within one timeout and triggers the
 * {@code TIMEOUT} fallback to vector order.
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
