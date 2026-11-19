package com.edupulse.retrievalpost;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Post-retrieval microservice handling cross-encoder reranking, neighbor stitching,
 * grounded citation generation, and RAG evaluation (Story 4).
 */
@SpringBootApplication
public class RetrievalPostApplication {

    public static void main(String[] args) {
        SpringApplication.run(RetrievalPostApplication.class, args);
    }
}
