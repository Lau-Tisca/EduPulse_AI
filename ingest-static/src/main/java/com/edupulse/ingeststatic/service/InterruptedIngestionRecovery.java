package com.edupulse.ingeststatic.service;

import com.edupulse.ingeststatic.repository.VectorStoreChecksumRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Releases ingestion claims left behind when the service stopped mid-ingestion (crash, container
 * restart). Ingestion runs in a single synchronized instance, so no claim can be legitimately in
 * progress at startup; a remaining {@code CLAIMED} row would otherwise make the document be
 * skipped forever with only partial vectors indexed.
 */
@Component
public class InterruptedIngestionRecovery {

    private static final Logger log = LoggerFactory.getLogger(InterruptedIngestionRecovery.class);

    private final VectorStoreChecksumRepository checksumRepository;

    public InterruptedIngestionRecovery(VectorStoreChecksumRepository checksumRepository) {
        this.checksumRepository = checksumRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void releaseInterruptedClaims() {
        try {
            int reset = checksumRepository.resetInterruptedClaims();
            if (reset > 0) {
                log.warn("Reset {} interrupted static ingestion claim(s) to FAILED; "
                        + "affected documents will be re-ingested on the next run", reset);
            } else {
                log.info("No interrupted static ingestion claims found at startup");
            }
        } catch (DataAccessException ex) {
            log.error("Failed to reset interrupted static ingestion claims at startup", ex);
        }
    }
}
