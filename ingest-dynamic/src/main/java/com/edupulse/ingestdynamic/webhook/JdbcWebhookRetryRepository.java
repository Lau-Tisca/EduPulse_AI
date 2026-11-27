package com.edupulse.ingestdynamic.webhook;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL-backed {@link WebhookRetryRepository} persisting queued webhook ingestion jobs
 * across container restarts in {@code webhook_retry_queue}.
 */
@Repository
public class JdbcWebhookRetryRepository implements WebhookRetryRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcWebhookRetryRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final AtomicBoolean queueTableInitialized = new AtomicBoolean(false);

    public JdbcWebhookRetryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public long enqueue(String url, String docId, int maxAttempts) {
        ensureQueueTable();
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO webhook_retry_queue (url, doc_id, status, attempts, max_attempts, next_retry_at)
                VALUES (?, ?, 'PENDING', 0, ?, CURRENT_TIMESTAMP)
                RETURNING id
                """, Long.class, url, docId, maxAttempts);
        if (id == null) {
            throw new IllegalStateException("Failed to enqueue webhook job for url=" + url);
        }
        return id;
    }

    @Override
    public Optional<WebhookJob> claimJob(long jobId) {
        ensureQueueTable();
        String leaseToken = UUID.randomUUID().toString();
        try {
            WebhookJob job = jdbcTemplate.queryForObject("""
                    UPDATE webhook_retry_queue
                    SET status = 'PROCESSING',
                        lease_token = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                      AND (
                            (status IN ('PENDING', 'RETRY_PENDING') AND next_retry_at <= CURRENT_TIMESTAMP)
                            OR (status = 'PROCESSING' AND updated_at < CURRENT_TIMESTAMP - INTERVAL '15 minutes')
                      )
                    RETURNING id, url, doc_id, status, attempts, max_attempts, next_retry_at, last_error, lease_token
                    """,
                    (rs, rowNum) -> new WebhookJob(
                            rs.getLong("id"),
                            rs.getString("url"),
                            rs.getString("doc_id"),
                            rs.getString("status"),
                            rs.getInt("attempts"),
                            rs.getInt("max_attempts"),
                            rs.getTimestamp("next_retry_at").toInstant(),
                            rs.getString("last_error"),
                            rs.getString("lease_token")
                    ),
                    leaseToken, jobId);
            return Optional.ofNullable(job);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public List<Long> findDueJobIds(int limit) {
        ensureQueueTable();
        return jdbcTemplate.queryForList("""
                SELECT id
                FROM webhook_retry_queue
                WHERE (status IN ('PENDING', 'RETRY_PENDING') AND next_retry_at <= CURRENT_TIMESTAMP)
                   OR (status = 'PROCESSING' AND updated_at < CURRENT_TIMESTAMP - INTERVAL '15 minutes')
                ORDER BY next_retry_at ASC, id ASC
                LIMIT ?
                """, Long.class, limit);
    }

    @Override
    public void markCompleted(long jobId, String leaseToken) {
        int updated = jdbcTemplate.update("""
                UPDATE webhook_retry_queue
                SET status = 'COMPLETED', last_error = NULL, lease_token = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND status = 'PROCESSING'
                  AND lease_token = ?
                """, jobId, leaseToken);
        if (updated == 0) {
            log.warn("Skipped markCompleted for stale/superseded webhook lease: jobId={}", jobId);
        }
    }

    @Override
    public void scheduleRetry(long jobId, String leaseToken, int attempts, Instant nextRetryAt, String lastError) {
        int updated = jdbcTemplate.update("""
                UPDATE webhook_retry_queue
                SET status = 'RETRY_PENDING',
                    attempts = ?,
                    next_retry_at = ?,
                    last_error = ?,
                    lease_token = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND status = 'PROCESSING'
                  AND lease_token = ?
                """, attempts, Timestamp.from(nextRetryAt), truncateError(lastError), jobId, leaseToken);
        if (updated == 0) {
            log.warn("Skipped scheduleRetry for stale/superseded webhook lease: jobId={}", jobId);
        }
    }

    @Override
    public void markDeadLetter(long jobId, String leaseToken, int attempts, String lastError) {
        int updated = jdbcTemplate.update("""
                UPDATE webhook_retry_queue
                SET status = 'DEAD_LETTER',
                    attempts = ?,
                    last_error = ?,
                    lease_token = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND status = 'PROCESSING'
                  AND lease_token = ?
                """, attempts, truncateError(lastError), jobId, leaseToken);
        if (updated == 0) {
            log.warn("Skipped markDeadLetter for stale/superseded webhook lease: jobId={}", jobId);
        }
    }

    @Override
    public int resetStuckProcessing() {
        ensureQueueTable();
        // Restrict startup recovery to stale (>15m) PROCESSING leases so a restarting replica never
        // requeues jobs actively executing on another healthy instance.
        return jdbcTemplate.update("""
                UPDATE webhook_retry_queue
                SET status = 'RETRY_PENDING',
                    lease_token = NULL,
                    next_retry_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE status = 'PROCESSING'
                  AND updated_at < CURRENT_TIMESTAMP - INTERVAL '15 minutes'
                """);
    }

    private void ensureQueueTable() {
        if (queueTableInitialized.get()) {
            return;
        }
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS webhook_retry_queue (
                    id BIGSERIAL PRIMARY KEY,
                    url TEXT NOT NULL,
                    doc_id VARCHAR(255),
                    status VARCHAR(24) NOT NULL,
                    attempts INT NOT NULL DEFAULT 0,
                    max_attempts INT NOT NULL DEFAULT 3,
                    next_retry_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    last_error TEXT,
                    lease_token VARCHAR(64),
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                ALTER TABLE webhook_retry_queue
                ADD COLUMN IF NOT EXISTS lease_token VARCHAR(64)
                """);
        queueTableInitialized.set(true);
    }

    private static String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 1000 ? error.substring(0, 1000) : error;
    }
}
