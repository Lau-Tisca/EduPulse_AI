package com.edupulse.ingestdynamic.versioning;

import com.edupulse.ingestdynamic.dto.DocumentVersionInfo;
import java.util.List;
import java.util.Optional;

/**
 * Manages document version discovery, fenced claim locking, atomic {@code is_latest} cutover,
 * and partial-failure rollback in PostgreSQL / {@code vector_store}.
 */
public interface DocumentVersionRepository {

    /**
     * Snapshot of existing versions in {@code vector_store} (and {@code dynamic_ingest_state}).
     * {@code latestVersion} and {@code latestChecksum} are {@code null} if only unactivated
     * ({@code is_latest=false}) rows exist.
     */
    record VersionSnapshot(Integer latestVersion, int maxVersion, String latestChecksum) {
    }

    /**
     * Inspects {@code vector_store} (and {@code dynamic_ingest_state} for monotonic version numbering)
     * for existing versions of {@code docId}, interoperating with documents originally indexed by
     * {@code ingest-static}.
     */
    Optional<VersionSnapshot> findVersionSnapshot(String docId);

    /**
     * Atomically claims ingestion of {@code (docId, targetVersion, checksum)} in
     * {@code dynamic_ingest_state} and returns a unique fencing {@code claimToken} when acquired,
     * or {@link Optional#empty()} if another non-expired claim is in progress.
     */
    Optional<String> tryClaim(String docId, int targetVersion, String checksum);

    /**
     * Atomically verifies {@code claimToken} ownership, marks all prior {@code is_latest=true}
     * chunks of {@code docId} as {@code is_latest=false}, marks {@code newVersion} chunks as
     * {@code is_latest=true}, and sets {@code dynamic_ingest_state.status = 'COMPLETED'}.
     */
    void activateVersion(String docId, int newVersion, String checksum, String claimToken);

    /**
     * Verifies {@code claimToken} ownership, marks {@code dynamic_ingest_state.status = 'FAILED'},
     * and removes only unactivated partial chunks belonging to {@code failedVersion}.
     * No-op if the claim lease was already superseded by another worker.
     */
    void rollbackFailedVersion(String docId, int failedVersion, String checksum, String claimToken);

    /**
     * Resets stale {@code CLAIMED} rows (older than the 15-minute lease) left behind by an
     * interrupted process back to {@code FAILED} and cleans up their unactivated partial chunks.
     */
    int resetInterruptedClaims();

    /**
     * Returns all stored versions of {@code docId} ordered by ascending version number.
     */
    List<DocumentVersionInfo> findVersionHistory(String docId);
}
