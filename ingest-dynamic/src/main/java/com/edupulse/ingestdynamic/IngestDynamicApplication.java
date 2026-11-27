package com.edupulse.ingestdynamic;

import com.edupulse.ingestdynamic.config.IngestDynamicProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Dynamic web, remote PDF, GitHub, and watched-directory ingestion microservice (Story 2).
 */
@SpringBootApplication
@EnableConfigurationProperties(IngestDynamicProperties.class)
@EnableScheduling
public class IngestDynamicApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngestDynamicApplication.class, args);
    }
}
