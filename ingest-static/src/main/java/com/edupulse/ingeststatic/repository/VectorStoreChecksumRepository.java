package com.edupulse.ingeststatic.repository;

/**
 * Queries pgvector metadata for idempotency checks without invoking embedding APIs.
 */
public interface VectorStoreChecksumRepository {

    boolean existsByDocIdAndChecksum(String docId, String checksum);
}
