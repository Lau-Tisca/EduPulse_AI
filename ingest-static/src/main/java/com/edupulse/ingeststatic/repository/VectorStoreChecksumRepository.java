package com.edupulse.ingeststatic.repository;

/**
 * Queries pgvector metadata for idempotency checks without invoking embedding APIs.
 */
public interface VectorStoreChecksumRepository {

    boolean existsByDocIdAndChecksum(String docId, String checksum);

    /**
     * Returns {@code true} if {@code vector_store} already holds a dynamically versioned
     * ({@code version <> '1'}) or active ({@code is_latest = 'true'}) version of {@code docId}
     * that is not a failed static attempt awaiting retry. Prevents {@code ingest-static} from
     * creating a second {@code is_latest=true} version alongside {@code ingest-dynamic}.
     */
    default boolean hasActiveIndexedVersion(String docId) {
        return false;
    }

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
