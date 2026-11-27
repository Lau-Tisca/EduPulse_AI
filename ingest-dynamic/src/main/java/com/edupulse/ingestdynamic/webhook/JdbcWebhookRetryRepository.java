package com.edupulse.ingestdynamic.webhook;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL-backed {@link WebhookRetryRepository} persisting queued webhook ingestion jobs
 * across container restarts in {@code webhook_retry_queue}.
 */
@Repository
public class JdbcWebhookRetryRepository implements WebhookRetryRepository {

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
        try {
            WebhookJob job = jdbcTemplate.queryForObject("""
                    UPDATE webhook_retry_queue
                    SET status = 'PROCESSING', updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                      AND (
                            (status IN ('PENDING', 'RETRY_PENDING') AND next_retry_at <= CURRENT_TIMESTAMP)
                            OR (status = 'PROCESSING' AND updated_at < CURRENT_TIMESTAMP - INTERVAL '15 minutes')
                      )
                    RETURNING id, url, doc_id, status, attempts, max_attempts, next_retry_at, last_error
                    """,
                    (rs, rowNum) -> new WebhookJob(
                            rs.getLong("id"),
                            rs.getString("url"),
                            rs.getString("doc_id"),
                            rs.getString("status"),
                            rs.getInt("attempts"),
                            rs.getInt("max_attempts"),
                            rs.getTimestamp("next_retry_at").toInstant(),
                            rs.getString("last_error")
                    ),
                    jobId);
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
    public void markCompleted(long jobId) {
        jdbcTemplate.update("""
                UPDATE webhook_retry_queue
                SET status = 'COMPLETED', last_error = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, jobId);
    }

    @Override
    public void scheduleRetry(long jobId, int attempts, Instant nextRetryAt, String lastError) {
        jdbcTemplate.update("""
                UPDATE webhook_retry_queue
                SET status = 'RETRY_PENDING',
                    attempts = ?,
                    next_retry_at = ?,
                    last_error = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, attempts, Timestamp.from(nextRetryAt), truncateError(lastError), jobId);
    }

    @Override
    public void markDeadLetter(long jobId, int attempts, String lastError) {
        jdbcTemplate.update("""
                UPDATE webhook_retry_queue
                SET status = 'DEAD_LETTER',
                    attempts = ?,
                    last_error = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, attempts, truncateError(lastError), jobId);
    }

    @Override
    public int resetStuckProcessing() {
        ensureQueueTable();
        return jdbcTemplate.update("""
                UPDATE webhook_retry_queue
                SET status = 'RETRY_PENDING',
                    next_retry_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE status = 'PROCESSING'
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
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
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
