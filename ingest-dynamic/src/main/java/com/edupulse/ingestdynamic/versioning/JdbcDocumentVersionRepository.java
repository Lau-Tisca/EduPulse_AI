package com.edupulse.ingestdynamic.versioning;

import com.edupulse.ingestdynamic.dto.DocumentVersionInfo;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC implementation of {@link DocumentVersionRepository} operating against {@code vector_store}
 * and {@code dynamic_ingest_state}.
 * <p>
 * Metadata numbers are read as text and parsed in Java (never SQL-cast inside {@code WHERE}) so a
 * malformed row belonging to another document can never break version lookups.
 */
@Repository
public class JdbcDocumentVersionRepository implements DocumentVersionRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcDocumentVersionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<VersionSnapshot> findVersionSnapshot(String docId) {
        List<DocumentVersionInfo> versions = findVersionHistory(docId);
        if (versions.isEmpty()) {
            return Optional.empty();
        }
        int maxVersion = versions.stream()
                .mapToInt(DocumentVersionInfo::version)
                .max()
                .orElse(0);
        DocumentVersionInfo latest = versions.stream()
                .filter(DocumentVersionInfo::isLatest)
                .max(Comparator.comparingInt(DocumentVersionInfo::version))
                .orElse(versions.getLast());
        return Optional.of(new VersionSnapshot(latest.version(), maxVersion, latest.checksum()));
    }

    @Override
    public boolean tryClaim(String docId, int targetVersion, String checksum) {
        ensureStateTable();
        Boolean claimed;
        try {
            claimed = jdbcTemplate.queryForObject("""
                    INSERT INTO dynamic_ingest_state (doc_id, version, checksum, status)
                    VALUES (?, ?, ?, 'CLAIMED')
                    ON CONFLICT (doc_id) DO UPDATE
                        SET version = EXCLUDED.version,
                            checksum = EXCLUDED.checksum,
                            status = 'CLAIMED',
                            updated_at = CURRENT_TIMESTAMP
                    WHERE dynamic_ingest_state.status <> 'CLAIMED'
                    RETURNING TRUE
                    """, Boolean.class, docId, targetVersion, checksum);
        } catch (EmptyResultDataAccessException ex) {
            return false;
        }
        if (!Boolean.TRUE.equals(claimed)) {
            return false;
        }
        // Clean up any leftover partial chunks from an earlier failed attempt of this exact targetVersion only.
        jdbcTemplate.update("""
                DELETE FROM vector_store
                WHERE metadata->>'doc_id' = ?
                  AND metadata->>'version' = ?
                """, docId, String.valueOf(targetVersion));
        return true;
    }

    @Override
    @Transactional
    public void activateVersion(String docId, int newVersion, String checksum) {
        ensureStateTable();
        String versionStr = String.valueOf(newVersion);

        // Mark all previous latest chunks as historical (preserving full history).
        jdbcTemplate.update("""
                UPDATE vector_store
                SET metadata = jsonb_set(metadata::jsonb, '{is_latest}', 'false'::jsonb)::json
                WHERE metadata->>'doc_id' = ?
                  AND metadata->>'is_latest' = 'true'
                  AND metadata->>'version' <> ?
                """, docId, versionStr);

        // Atomically promote the newly written version's chunks to is_latest=true.
        jdbcTemplate.update("""
                UPDATE vector_store
                SET metadata = jsonb_set(metadata::jsonb, '{is_latest}', 'true'::jsonb)::json
                WHERE metadata->>'doc_id' = ?
                  AND metadata->>'version' = ?
                """, docId, versionStr);

        jdbcTemplate.update("""
                UPDATE dynamic_ingest_state
                SET status = 'COMPLETED', updated_at = CURRENT_TIMESTAMP
                WHERE doc_id = ? AND version = ? AND checksum = ?
                """, docId, newVersion, checksum);
    }

    @Override
    public void rollbackFailedVersion(String docId, int failedVersion, String checksum) {
        ensureStateTable();
        // Delete ONLY partial chunks of the failed version; never touch previous versions.
        jdbcTemplate.update("""
                DELETE FROM vector_store
                WHERE metadata->>'doc_id' = ?
                  AND metadata->>'version' = ?
                """, docId, String.valueOf(failedVersion));

        jdbcTemplate.update("""
                UPDATE dynamic_ingest_state
                SET status = 'FAILED', updated_at = CURRENT_TIMESTAMP
                WHERE doc_id = ? AND version = ? AND checksum = ?
                """, docId, failedVersion, checksum);
    }

    @Override
    public int resetInterruptedClaims() {
        ensureStateTable();
        return jdbcTemplate.update("""
                UPDATE dynamic_ingest_state
                SET status = 'FAILED', updated_at = CURRENT_TIMESTAMP
                WHERE status = 'CLAIMED'
                """);
    }

    @Override
    public List<DocumentVersionInfo> findVersionHistory(String docId) {
        List<DocumentVersionInfo> rows = jdbcTemplate.query("""
                SELECT metadata->>'version' AS version,
                       metadata->>'checksum' AS checksum,
                       metadata->>'is_latest' AS is_latest,
                       COUNT(*) AS chunk_count
                FROM vector_store
                WHERE metadata->>'doc_id' = ?
                GROUP BY metadata->>'version', metadata->>'checksum', metadata->>'is_latest'
                """,
                (rs, rowNum) -> {
                    Integer version = parseIntOrNull(rs.getString("version"));
                    if (version == null) {
                        return null;
                    }
                    String checksum = rs.getString("checksum");
                    boolean isLatest = "true".equalsIgnoreCase(rs.getString("is_latest"));
                    int chunkCount = rs.getInt("chunk_count");
                    return new DocumentVersionInfo(version, checksum == null ? "" : checksum, isLatest, chunkCount);
                },
                docId);

        return rows.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(DocumentVersionInfo::version))
                .toList();
    }

    private void ensureStateTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS dynamic_ingest_state (
                    doc_id VARCHAR(255) PRIMARY KEY,
                    version INT NOT NULL,
                    checksum VARCHAR(64) NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }

    private static Integer parseIntOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
