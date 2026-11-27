package com.edupulse.ingestdynamic.webhook;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence contract for the PostgreSQL-backed webhook retry queue ({@code webhook_retry_queue}).
 * Each claimed job carries a unique {@code leaseToken} fencing token so stale workers whose
 * processing lease expired cannot overwrite a newer worker's result.
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
            String lastError,
            String leaseToken
    ) {
        public WebhookJob(
                long id,
                String url,
                String docId,
                String status,
                int attempts,
                int maxAttempts,
                Instant nextRetryAt,
                String lastError
        ) {
            this(id, url, docId, status, attempts, maxAttempts, nextRetryAt, lastError, "");
        }
    }

    long enqueue(String url, String docId, int maxAttempts);

    Optional<WebhookJob> claimJob(long jobId);

    List<Long> findDueJobIds(int limit);

    void markCompleted(long jobId, String leaseToken);

    void scheduleRetry(long jobId, String leaseToken, int attempts, Instant nextRetryAt, String lastError);

    void markDeadLetter(long jobId, String leaseToken, int attempts, String lastError);

    int resetStuckProcessing();
}
