package com.edupulse.ingeststatic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized configuration for static PDF ingestion mapped from APP_* environment variables.
 */
@ConfigurationProperties(prefix = "app")
public record IngestStaticProperties(
        int chunkSize,
        int chunkOverlap,
        String staticResourcesDir
) {
    public IngestStaticProperties {
        if (chunkSize <= 0) {
            chunkSize = 512;
        }
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            chunkOverlap = Math.min(64, Math.max(0, chunkSize / 8));
        }
        if (staticResourcesDir == null || staticResourcesDir.isBlank()) {
            staticResourcesDir = "/app/resources";
        }
    }
}
