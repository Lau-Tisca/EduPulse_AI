package com.edupulse.ingestdynamic.versioning;

import com.edupulse.ingestdynamic.webhook.WebhookRetryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Recovers {@code dynamic_ingest_state} rows stuck in {@code CLAIMED} and
 * {@code webhook_retry_queue} rows stuck in {@code PROCESSING} after an abrupt restart.
 */
@Component
public class InterruptedDynamicIngestionRecovery {

    private static final Logger log = LoggerFactory.getLogger(InterruptedDynamicIngestionRecovery.class);

    private final DocumentVersionRepository versionRepository;
    private final WebhookRetryRepository webhookRetryRepository;

    public InterruptedDynamicIngestionRecovery(
            DocumentVersionRepository versionRepository,
            WebhookRetryRepository webhookRetryRepository
    ) {
        this.versionRepository = versionRepository;
        this.webhookRetryRepository = webhookRetryRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedWork() {
        try {
            int resetClaims = versionRepository.resetInterruptedClaims();
            if (resetClaims > 0) {
                log.warn("Reset {} interrupted dynamic ingestion claim(s) from CLAIMED to FAILED on startup", resetClaims);
            }
        } catch (DataAccessException ex) {
            log.warn("Could not run dynamic ingestion startup recovery: {}", ex.getMessage());
        }

        try {
            int resetJobs = webhookRetryRepository.resetStuckProcessing();
            if (resetJobs > 0) {
                log.warn("Reset {} interrupted webhook job(s) from PROCESSING to RETRY_PENDING on startup", resetJobs);
            }
        } catch (DataAccessException ex) {
            log.warn("Could not run webhook retry queue startup recovery: {}", ex.getMessage());
        }
    }
}
