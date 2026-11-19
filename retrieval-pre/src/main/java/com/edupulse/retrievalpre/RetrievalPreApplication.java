package com.edupulse.retrievalpre;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Pre-retrieval microservice handling metadata filtering, query normalization,
 * acronym expansion, and multi-query RRF retrieval (Story 3).
 */
@SpringBootApplication
public class RetrievalPreApplication {

    public static void main(String[] args) {
        SpringApplication.run(RetrievalPreApplication.class, args);
    }
}
