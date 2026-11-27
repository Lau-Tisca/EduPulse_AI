package com.edupulse.ingestdynamic.versioning;

import com.edupulse.ingestdynamic.dto.DocumentVersionInfo;
import com.edupulse.ingestdynamic.exception.IngestionConflictException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC implementation of {@link DocumentVersionRepository} operating against {@code vector_store}
 * and {@code dynamic_ingest_state}.
 * <p>
 * Metadata numbers are read as text and parsed in Java (never SQL-cast inside {@code WHERE}) so a
 * malformed row belonging to another document can never break version lookups. Claims carry a
 * unique {@code claim_token} fencing token so stale workers whose lease expired cannot promote
 * obsolete chunks or delete a replacement worker's chunks.
 */
@Repository
public class JdbcDocumentVersionRepository implements DocumentVersionRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcDocumentVersionRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final AtomicBoolean stateTableInitialized = new AtomicBoolean(false);

    public JdbcDocumentVersionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<VersionSnapshot> findVersionSnapshot(String docId) {
        List<DocumentVersionInfo> versions = findVersionHistory(docId);
        int stateMaxVersion = findStateVersion(docId).orElse(0);
        if (versions.isEmpty() && stateMaxVersion == 0) {
            return Optional.empty();
        }
        int vectorMaxVersion = versions.stream()
                .mapToInt(DocumentVersionInfo::version)
                .max()
                .orElse(0);
        // Taking max(vector_store, dynamic_ingest_state) ensures a replacement worker after a lease
        // expiry always claims a strictly higher version number, preventing chunk interleaving.
        int maxVersion = Math.max(vectorMaxVersion, stateMaxVersion);

        // Only treat rows with is_latest=true as the active version; never fall back to unactivated
        // partial rows (otherwise a crashed v1 ingestion would permanently skip future retries).
        Optional<DocumentVersionInfo> latestOpt = versions.stream()
                .filter(DocumentVersionInfo::isLatest)
                .max(Comparator.comparingInt(DocumentVersionInfo::version));
        return Optional.of(new VersionSnapshot(
                latestOpt.map(DocumentVersionInfo::version).orElse(null),
                maxVersion,
                latestOpt.map(DocumentVersionInfo::checksum).orElse(null)
        ));
    }

    @Override
    @Transactional
    public Optional<String> tryClaim(String docId, int targetVersion, String checksum) {
        ensureStateTable();
        // Clean up any unactivated partial chunks left behind by a prior FAILED or expired (>15m) CLAIMED attempt.
        jdbcTemplate.update("""
                DELETE FROM vector_store v
                USING dynamic_ingest_state s
                WHERE s.doc_id = ?
                  AND (s.status = 'FAILED'
                       OR (s.status = 'CLAIMED' AND s.updated_at < CURRENT_TIMESTAMP - INTERVAL '15 minutes'))
                  AND v.metadata->>'doc_id' = s.doc_id
                  AND v.metadata->>'version' = s.version::text
                  AND v.metadata->>'is_latest' = 'false'
                """, docId);

        String claimToken = UUID.randomUUID().toString();
        Boolean claimed;
        try {
            claimed = jdbcTemplate.queryForObject("""
                    INSERT INTO dynamic_ingest_state (doc_id, version, checksum, status, claim_token)
                    VALUES (?, ?, ?, 'CLAIMED', ?)
                    ON CONFLICT (doc_id) DO UPDATE
                        SET version = EXCLUDED.version,
                            checksum = EXCLUDED.checksum,
                            status = 'CLAIMED',
                            claim_token = EXCLUDED.claim_token,
                            updated_at = CURRENT_TIMESTAMP
                    WHERE dynamic_ingest_state.status <> 'CLAIMED'
                       OR dynamic_ingest_state.updated_at < CURRENT_TIMESTAMP - INTERVAL '15 minutes'
                    RETURNING TRUE
                    """, Boolean.class, docId, targetVersion, checksum, claimToken);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
        if (!Boolean.TRUE.equals(claimed)) {
            return Optional.empty();
        }
        // Clean up any leftover partial chunks from an earlier failed attempt of this exact targetVersion only.
        jdbcTemplate.update("""
                DELETE FROM vector_store
                WHERE metadata->>'doc_id' = ?
                  AND metadata->>'version' = ?
                  AND metadata->>'is_latest' = 'false'
                """, docId, String.valueOf(targetVersion));
        return Optional.of(claimToken);
    }

    @Override
    @Transactional
    public void activateVersion(String docId, int newVersion, String checksum, String claimToken) {
        ensureStateTable();
        // Fence FIRST inside the transaction (acquiring the row lock on dynamic_ingest_state) so a
        // stale worker whose lease expired can never demote active versions or promote stale chunks.
        int stateUpdated = jdbcTemplate.update("""
                UPDATE dynamic_ingest_state
                SET status = 'COMPLETED', updated_at = CURRENT_TIMESTAMP
                WHERE doc_id = ?
                  AND version = ?
                  AND checksum = ?
                  AND status = 'CLAIMED'
                  AND claim_token = ?
                """, docId, newVersion, checksum, claimToken);
        if (stateUpdated == 0) {
            throw new IngestionConflictException(
                    "Ingestion claim lease expired or was superseded for docId='" + docId + "' version=" + newVersion);
        }

        String versionStr = String.valueOf(newVersion);

        // Mark all previous latest chunks as historical (preserving full history).
        jdbcTemplate.update("""
                UPDATE vector_store
                SET metadata = jsonb_set(metadata::jsonb, '{is_latest}', 'false'::jsonb)::json
                WHERE metadata->>'doc_id' = ?
                  AND metadata->>'is_latest' = 'true'
                  AND COALESCE(metadata->>'version', '') <> ?
                """, docId, versionStr);

        // Atomically promote the newly written version's chunks to is_latest=true.
        int promoted = jdbcTemplate.update("""
                UPDATE vector_store
                SET metadata = jsonb_set(metadata::jsonb, '{is_latest}', 'true'::jsonb)::json
                WHERE metadata->>'doc_id' = ?
                  AND metadata->>'version' = ?
                """, docId, versionStr);
        if (promoted == 0) {
            throw new IllegalStateException(
                    "No chunks found to activate for docId='" + docId + "' version=" + newVersion
                            + "; rolling back is_latest cutover");
        }
    }

    @Override
    @Transactional
    public void rollbackFailedVersion(String docId, int failedVersion, String checksum, String claimToken) {
        ensureStateTable();
        // Fence FIRST: only roll back if this worker still owns the active CLAIMED token.
        int stateUpdated = jdbcTemplate.update("""
                UPDATE dynamic_ingest_state
                SET status = 'FAILED', updated_at = CURRENT_TIMESTAMP
                WHERE doc_id = ?
                  AND version = ?
                  AND checksum = ?
                  AND status = 'CLAIMED'
                  AND claim_token = ?
                """, docId, failedVersion, checksum, claimToken);
        if (stateUpdated == 0) {
            log.warn("Skipped rollback for stale/superseded claim: docId={}, version={}", docId, failedVersion);
            return;
        }

        // Delete ONLY unactivated partial chunks of the failed version; never touch previous versions.
        jdbcTemplate.update("""
                DELETE FROM vector_store
                WHERE metadata->>'doc_id' = ?
                  AND metadata->>'version' = ?
                  AND metadata->>'is_latest' = 'false'
                """, docId, String.valueOf(failedVersion));
    }

    @Override
    @Transactional
    public int resetInterruptedClaims() {
        ensureStateTable();
        // Restrict startup recovery to stale (>15m) CLAIMED leases so a restarting replica never
        // disrupts an active ingestion running on another healthy instance.
        jdbcTemplate.update("""
                DELETE FROM vector_store v
                USING dynamic_ingest_state s
                WHERE s.status = 'CLAIMED'
                  AND s.updated_at < CURRENT_TIMESTAMP - INTERVAL '15 minutes'
                  AND v.metadata->>'doc_id' = s.doc_id
                  AND v.metadata->>'version' = s.version::text
                  AND v.metadata->>'is_latest' = 'false'
                """);

        return jdbcTemplate.update("""
                UPDATE dynamic_ingest_state
                SET status = 'FAILED', updated_at = CURRENT_TIMESTAMP
                WHERE status = 'CLAIMED'
                  AND updated_at < CURRENT_TIMESTAMP - INTERVAL '15 minutes'
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

    private Optional<Integer> findStateVersion(String docId) {
        ensureStateTable();
        try {
            Integer version = jdbcTemplate.queryForObject("""
                    SELECT version
                    FROM dynamic_ingest_state
                    WHERE doc_id = ?
                    """, Integer.class, docId);
            return Optional.ofNullable(version);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    private void ensureStateTable() {
        if (stateTableInitialized.get()) {
            return;
        }
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS dynamic_ingest_state (
                    doc_id VARCHAR(255) PRIMARY KEY,
                    version INT NOT NULL,
                    checksum VARCHAR(64) NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    claim_token VARCHAR(64),
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                ALTER TABLE dynamic_ingest_state
                ADD COLUMN IF NOT EXISTS claim_token VARCHAR(64)
                """);
        stateTableInitialized.set(true);
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
