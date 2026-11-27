package com.edupulse.ingestdynamic.webhook;

import com.edupulse.ingestdynamic.dto.DynamicIngestResult;
import com.edupulse.ingestdynamic.exception.ContentParseException;
import com.edupulse.ingestdynamic.exception.InvalidUrlException;
import com.edupulse.ingestdynamic.exception.RemoteFetchException;
import com.edupulse.ingestdynamic.exception.SsrfBlockedException;
import com.edupulse.ingestdynamic.service.DynamicIngestService;
import com.edupulse.ingestdynamic.webhook.WebhookRetryRepository.WebhookJob;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Processes verified webhook ingestion targets asynchronously with a PostgreSQL-backed retry queue
 * and exponential backoff. Permanent failures (SSRF blocks, invalid URLs, unparsable content, 413)
 * move immediately to {@code DEAD_LETTER}; transient errors retry up to {@code DEFAULT_MAX_ATTEMPTS}.
 */
@Service
public class WebhookProcessingService {

    private static final Logger log = LoggerFactory.getLogger(WebhookProcessingService.class);
    static final int DEFAULT_MAX_ATTEMPTS = 3;
    static final long BASE_BACKOFF_SECONDS = 15L;
    private static final int POLL_BATCH_SIZE = 10;

    private final WebhookRetryRepository retryRepository;
    private final DynamicIngestService dynamicIngestService;
    private final Executor webhookTaskExecutor;

    public WebhookProcessingService(
            WebhookRetryRepository retryRepository,
            DynamicIngestService dynamicIngestService,
            @Qualifier("webhookTaskExecutor") Executor webhookTaskExecutor
    ) {
        this.retryRepository = retryRepository;
        this.dynamicIngestService = dynamicIngestService;
        this.webhookTaskExecutor = webhookTaskExecutor;
    }

    public List<Long> enqueueAndDispatch(List<WebhookTarget> targets) {
        List<Long> jobIds = new ArrayList<>(targets.size());
        for (WebhookTarget target : targets) {
            long jobId = retryRepository.enqueue(target.url(), target.docId(), DEFAULT_MAX_ATTEMPTS);
            jobIds.add(jobId);
            log.info("Enqueued webhook ingestion job: jobId={}, url={}, docId={}",
                    jobId, sanitize(target.url()), sanitize(target.docId()));
            dispatchAsync(jobId);
        }
        return jobIds;
    }

    @Scheduled(
            fixedDelayString = "${app.webhook.retry-poll-ms:15000}",
            initialDelayString = "${app.webhook.retry-poll-ms:15000}"
    )
    public void pollPendingRetries() {
        List<Long> dueIds;
        try {
            dueIds = retryRepository.findDueJobIds(POLL_BATCH_SIZE);
        } catch (DataAccessException ex) {
            log.warn("Webhook retry poller could not query webhook_retry_queue: {}", ex.getMessage());
            return;
        }
        for (Long jobId : dueIds) {
            dispatchAsync(jobId);
        }
    }

    private void dispatchAsync(long jobId) {
        try {
            webhookTaskExecutor.execute(() -> processJob(jobId));
        } catch (RejectedExecutionException ex) {
            log.warn("Webhook executor saturated; jobId={} remains persisted in webhook_retry_queue for retry poller",
                    jobId);
        }
    }

    public void processJob(long jobId) {
        Optional<WebhookJob> claimedOpt = retryRepository.claimJob(jobId);
        if (claimedOpt.isEmpty()) {
            return;
        }
        WebhookJob job = claimedOpt.get();
        int attempt = job.attempts() + 1;

        try {
            DynamicIngestResult result = dynamicIngestService.ingestUrl(job.url(), job.docId());
            retryRepository.markCompleted(job.id(), job.leaseToken());
            log.info("Webhook job completed: jobId={}, attempt={}/{}, docId={}, status={}, version={}, chunks={}",
                    job.id(), attempt, job.maxAttempts(), result.docId(), result.status(),
                    result.version(), result.chunksCreated());
        } catch (RuntimeException ex) {
            String error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            if (isPermanentFailure(ex) || attempt >= job.maxAttempts()) {
                retryRepository.markDeadLetter(job.id(), job.leaseToken(), attempt, error);
                log.warn("Webhook job moved to DEAD_LETTER: jobId={}, attempt={}/{}, url={}, reason={}",
                        job.id(), attempt, job.maxAttempts(), sanitize(job.url()), sanitize(error));
            } else {
                long delaySeconds = BASE_BACKOFF_SECONDS * (1L << Math.min(attempt - 1, 5));
                Instant nextRetryAt = Instant.now().plusSeconds(delaySeconds);
                retryRepository.scheduleRetry(job.id(), job.leaseToken(), attempt, nextRetryAt, error);
                log.warn("Webhook job failed; scheduled retry: jobId={}, attempt={}/{}, nextRetryInSec={}, url={}, reason={}",
                        job.id(), attempt, job.maxAttempts(), delaySeconds, sanitize(job.url()), sanitize(error));
            }
        }
    }

    static boolean isPermanentFailure(Throwable ex) {
        if (ex instanceof SsrfBlockedException
                || ex instanceof InvalidUrlException
                || ex instanceof ContentParseException) {
            return true;
        }
        return ex instanceof RemoteFetchException fetchEx
                && fetchEx.status() == HttpStatus.CONTENT_TOO_LARGE;
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ');
    }
}
