package com.edupulse.ingestdynamic.config;

import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized configuration for dynamic ingestion (Story 2) mapped from APP_* and VENDOR_* env vars.
 * Invalid numeric values fall back to agreed defaults with a WARN log.
 */
@ConfigurationProperties(prefix = "app")
public record IngestDynamicProperties(
        int chunkSize,
        int chunkOverlap,
        Watcher watcher,
        Ingest ingest,
        Webhook webhook
) {

    private static final Logger log = LoggerFactory.getLogger(IngestDynamicProperties.class);

    public static final int DEFAULT_CHUNK_SIZE = 512;
    public static final int DEFAULT_CHUNK_OVERLAP = 64;

    public IngestDynamicProperties {
        if (chunkSize <= 0) {
            log.warn("Invalid APP_CHUNK_SIZE={}; using default {}", chunkSize, DEFAULT_CHUNK_SIZE);
            chunkSize = DEFAULT_CHUNK_SIZE;
        }
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            int fallback = Math.min(DEFAULT_CHUNK_OVERLAP, Math.max(0, chunkSize / 8));
            log.warn("Invalid APP_CHUNK_OVERLAP={} for chunkSize={}; using {}", chunkOverlap, chunkSize, fallback);
            chunkOverlap = fallback;
        }
        if (watcher == null) {
            watcher = Watcher.defaults();
        }
        if (ingest == null) {
            ingest = Ingest.defaults();
        }
        if (webhook == null) {
            webhook = new Webhook("");
        }
    }

    public record Watcher(
            int intervalSec,
            String resourcesDir
    ) {
        public static final int DEFAULT_INTERVAL_SEC = 30;
        public static final String DEFAULT_RESOURCES_DIR = "/app/resources";

        public Watcher {
            if (intervalSec <= 0) {
                log.warn("Invalid APP_WATCHER_INTERVAL_SEC={}; using default {}", intervalSec, DEFAULT_INTERVAL_SEC);
                intervalSec = DEFAULT_INTERVAL_SEC;
            }
            if (resourcesDir == null || resourcesDir.isBlank()) {
                resourcesDir = DEFAULT_RESOURCES_DIR;
            }
        }

        public static Watcher defaults() {
            return new Watcher(DEFAULT_INTERVAL_SEC, DEFAULT_RESOURCES_DIR);
        }
    }

    public record Ingest(
            long maxBytes,
            int timeoutMs,
            boolean blockPrivateNetworks,
            List<String> allowedDomains
    ) {
        public static final long DEFAULT_MAX_BYTES = 20_971_520L;
        public static final int DEFAULT_TIMEOUT_MS = 10_000;
        public static final List<String> DEFAULT_ALLOWED_DOMAINS = List.of(
                "kubernetes.io",
                "github.com",
                "raw.githubusercontent.com",
                "cncf.io"
        );

        public Ingest {
            if (maxBytes <= 0) {
                log.warn("Invalid APP_INGEST_MAX_BYTES={}; using default {}", maxBytes, DEFAULT_MAX_BYTES);
                maxBytes = DEFAULT_MAX_BYTES;
            }
            if (timeoutMs <= 0) {
                log.warn("Invalid APP_INGEST_TIMEOUT_MS={}; using default {}", timeoutMs, DEFAULT_TIMEOUT_MS);
                timeoutMs = DEFAULT_TIMEOUT_MS;
            }
            List<String> normalized = allowedDomains == null
                    ? List.of()
                    : allowedDomains.stream()
                            .filter(d -> d != null && !d.isBlank())
                            .map(d -> d.trim().toLowerCase(Locale.ROOT))
                            .map(d -> d.startsWith(".") ? d.substring(1) : d)
                            .map(d -> d.endsWith(".") ? d.substring(0, d.length() - 1) : d)
                            .filter(d -> !d.isEmpty())
                            .distinct()
                            .toList();
            if (normalized.isEmpty()) {
                log.warn("Empty APP_INGEST_ALLOWED_DOMAINS; using default {}", DEFAULT_ALLOWED_DOMAINS);
                allowedDomains = DEFAULT_ALLOWED_DOMAINS;
            } else {
                allowedDomains = normalized;
            }
        }

        public static Ingest defaults() {
            return new Ingest(DEFAULT_MAX_BYTES, DEFAULT_TIMEOUT_MS, true, DEFAULT_ALLOWED_DOMAINS);
        }
    }

    public record Webhook(String secret) {
        public Webhook {
            secret = secret == null ? "" : secret.trim();
        }

        public boolean hasSecret() {
            return !secret.isEmpty();
        }

        @Override
        public String toString() {
            return "Webhook[secret=" + (hasSecret() ? "***" : "<empty>") + "]";
        }
    }
}
