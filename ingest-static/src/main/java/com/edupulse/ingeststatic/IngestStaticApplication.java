package com.edupulse.ingeststatic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Static PDF ingestion microservice (Story 1) and sole owner of the pgvector schema initialization.
 */
@SpringBootApplication
public class IngestStaticApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngestStaticApplication.class, args);
    }
}
