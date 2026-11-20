package com.edupulse.ingeststatic.service;

import com.google.genai.errors.ClientException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

/**
 * Writes chunked documents to pgvector in bounded batches with automatic retry/backoff
 * when Google AI Studio Free Tier returns HTTP 429 rate-limit responses.
 */
@Service
public class VectorStoreBatchWriter {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreBatchWriter.class);
    private static final int DEFAULT_BATCH_SIZE = 20;
    private static final int DEFAULT_MAX_RETRIES = 4;
    private static final long DEFAULT_RETRY_DELAY_MS = 15_000L;

    private final VectorStore vectorStore;
    private final int batchSize;
    private final int maxRetries;
    private final long retryDelayMs;

    @org.springframework.beans.factory.annotation.Autowired
    public VectorStoreBatchWriter(VectorStore vectorStore) {
        this(vectorStore, DEFAULT_BATCH_SIZE, DEFAULT_MAX_RETRIES, DEFAULT_RETRY_DELAY_MS);
    }

    VectorStoreBatchWriter(VectorStore vectorStore, int batchSize, int maxRetries, long retryDelayMs) {
        this.vectorStore = vectorStore;
        this.batchSize = batchSize;
        this.maxRetries = maxRetries;
        this.retryDelayMs = retryDelayMs;
    }

    public void addAll(List<Document> chunks, String docId) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        int total = chunks.size();
        for (int start = 0; start < total; start += batchSize) {
            int end = Math.min(start + batchSize, total);
            List<Document> batch = chunks.subList(start, end);
            addBatchWithRetry(batch, docId, start + 1, end, total);
        }
    }

    private void addBatchWithRetry(List<Document> batch, String docId, int from, int to, int total) {
        int attempt = 0;
        while (true) {
            try {
                vectorStore.add(batch);
                return;
            } catch (RuntimeException ex) {
                if (!isRateLimitException(ex) || attempt >= maxRetries) {
                    throw ex;
                }
                attempt++;
                long sleepMs = retryDelayMs * attempt;
                log.warn("Rate limit (HTTP 429) while indexing docId={} chunks {}-{}/{}; retry {}/{} in {} ms",
                        docId, from, to, total, attempt, maxRetries, sleepMs);
                sleepQuietly(sleepMs);
            }
        }
    }

    private boolean isRateLimitException(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof ClientException clientEx && clientEx.code() == 429) {
                return true;
            }
            String msg = current.getMessage();
            if (msg != null && (msg.contains("429") || msg.contains("rate-limit") || msg.contains("Quota exceeded"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    protected void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for embedding rate-limit backoff", ex);
        }
    }
}
