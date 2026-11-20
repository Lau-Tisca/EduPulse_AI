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
}
