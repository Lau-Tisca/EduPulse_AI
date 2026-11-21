package com.edupulse.ingeststatic.repository;

/**
 * Queries pgvector metadata for idempotency checks without invoking embedding APIs.
 */
public interface VectorStoreChecksumRepository {

    boolean existsByDocIdAndChecksum(String docId, String checksum);

    boolean tryClaim(String docId, String checksum);

    void markCompleted(String docId, String checksum);

    void markFailed(String docId, String checksum);

    void deleteByDocIdAndChecksum(String docId, String checksum);

    /**
     * Marks every ingestion left in {@code CLAIMED} state as {@code FAILED} so the next
     * ingestion run re-claims it and replaces its partial vectors.
     *
     * @return number of interrupted claims that were reset
     */
    int resetInterruptedClaims();
}
