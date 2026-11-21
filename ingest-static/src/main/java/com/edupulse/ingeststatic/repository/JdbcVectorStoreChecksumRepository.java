package com.edupulse.ingeststatic.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcVectorStoreChecksumRepository implements VectorStoreChecksumRepository {

    private static final String EXISTS_SQL = """
            SELECT COUNT(*)
            FROM vector_store
            WHERE metadata->>'doc_id' = ?
              AND metadata->>'checksum' = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcVectorStoreChecksumRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean tryClaim(String docId, String checksum) {
        ensureStateTable();

        Boolean claimed;
        try {
            claimed = jdbcTemplate.queryForObject("""
                    INSERT INTO static_ingest_state (doc_id, checksum, status)
                    VALUES (?, ?, 'CLAIMED')
                    ON CONFLICT (doc_id) DO UPDATE
                        SET checksum = EXCLUDED.checksum,
                            status = 'CLAIMED',
                            updated_at = CURRENT_TIMESTAMP
                    WHERE static_ingest_state.status = 'FAILED'
                       OR static_ingest_state.checksum <> EXCLUDED.checksum
                    RETURNING TRUE
                    """, Boolean.class, docId, checksum);
        } catch (EmptyResultDataAccessException ex) {
            return false;
        }
        jdbcTemplate.update("""
                DELETE FROM vector_store
                WHERE metadata->>'doc_id' = ? AND metadata->>'checksum' = ?
                """, docId, checksum);
        return true;
    }

    @Override
    public int resetInterruptedClaims() {
        ensureStateTable();
        return jdbcTemplate.update("""
                UPDATE static_ingest_state
                SET status = 'FAILED', updated_at = CURRENT_TIMESTAMP
                WHERE status = 'CLAIMED'
                """);
    }

    @Override
    public void markCompleted(String docId, String checksum) {
        jdbcTemplate.update("""
                UPDATE static_ingest_state
                SET status = 'COMPLETED', updated_at = CURRENT_TIMESTAMP
                WHERE doc_id = ? AND checksum = ?
                """, docId, checksum);
    }

    @Override
    public void markFailed(String docId, String checksum) {
        jdbcTemplate.update("""
                UPDATE static_ingest_state
                SET status = 'FAILED', updated_at = CURRENT_TIMESTAMP
                WHERE doc_id = ? AND checksum = ?
                """, docId, checksum);
    }

    @Override
    public void deleteByDocIdAndChecksum(String docId, String checksum) {
        jdbcTemplate.update("""
                DELETE FROM vector_store
                WHERE metadata->>'doc_id' = ?
                """, docId);
    }

    @Override
    public boolean existsByDocIdAndChecksum(String docId, String checksum) {
        Integer count = jdbcTemplate.queryForObject(EXISTS_SQL, Integer.class, docId, checksum);
        return count != null && count > 0;
    }

    private void ensureStateTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS static_ingest_state (
                    doc_id VARCHAR(255) PRIMARY KEY,
                    checksum VARCHAR(64) NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }
}
