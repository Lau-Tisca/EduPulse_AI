package com.edupulse.ingestdynamic.webhook;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence contract for the PostgreSQL-backed webhook retry queue ({@code webhook_retry_queue}).
 */
public interface WebhookRetryRepository {

    String STATUS_PENDING = "PENDING";
    String STATUS_PROCESSING = "PROCESSING";
    String STATUS_COMPLETED = "COMPLETED";
    String STATUS_RETRY_PENDING = "RETRY_PENDING";
    String STATUS_DEAD_LETTER = "DEAD_LETTER";

    record WebhookJob(
            long id,
            String url,
            String docId,
            String status,
            int attempts,
            int maxAttempts,
            Instant nextRetryAt,
            String lastError
    ) {
    }

    long enqueue(String url, String docId, int maxAttempts);

    Optional<WebhookJob> claimJob(long jobId);

    List<Long> findDueJobIds(int limit);

    void markCompleted(long jobId);

    void scheduleRetry(long jobId, int attempts, Instant nextRetryAt, String lastError);

    void markDeadLetter(long jobId, int attempts, String lastError);

    int resetStuckProcessing();
}
