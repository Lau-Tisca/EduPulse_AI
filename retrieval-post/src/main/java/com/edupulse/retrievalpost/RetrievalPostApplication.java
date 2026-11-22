package com.edupulse.retrievalpost;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Post-retrieval microservice handling cross-encoder reranking, neighbor stitching,
 * grounded citation generation, and RAG evaluation (Story 4).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RetrievalPostApplication {

    public static void main(String[] args) {
        SpringApplication.run(RetrievalPostApplication.class, args);
    }
}
