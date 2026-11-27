package com.edupulse.ingestdynamic.versioning;

import com.edupulse.ingestdynamic.dto.DocumentVersionInfo;
import java.util.List;
import java.util.Optional;

/**
 * Manages document version discovery, claim locking, atomic {@code is_latest} cutover,
 * and partial-failure rollback in PostgreSQL / {@code vector_store}.
 */
public interface DocumentVersionRepository {

    record VersionSnapshot(int latestVersion, int maxVersion, String latestChecksum) {
    }

    /**
     * Inspects {@code vector_store} for existing versions of {@code docId} (including documents
     * originally indexed by {@code ingest-static}).
     */
    Optional<VersionSnapshot> findVersionSnapshot(String docId);

    /**
     * Atomically claims ingestion of {@code (docId, targetVersion, checksum)} in
     * {@code dynamic_ingest_state} and removes any stale partial chunks for {@code targetVersion}.
     *
     * @return {@code true} if claimed, {@code false} if already in progress or completed
     */
    boolean tryClaim(String docId, int targetVersion, String checksum);

    /**
     * Atomically marks all prior {@code is_latest=true} chunks of {@code docId} as
     * {@code is_latest=false}, marks {@code newVersion} chunks as {@code is_latest=true},
     * and sets {@code dynamic_ingest_state.status = 'COMPLETED'}.
     */
    void activateVersion(String docId, int newVersion, String checksum);

    /**
     * Removes only partial chunks belonging to {@code failedVersion} (never touching previous
     * versions) and marks {@code dynamic_ingest_state.status = 'FAILED'}.
     */
    void rollbackFailedVersion(String docId, int failedVersion, String checksum);

    /**
     * Resets any {@code CLAIMED} rows left behind by an interrupted process back to {@code FAILED}.
     */
    int resetInterruptedClaims();

    /**
     * Returns all stored versions of {@code docId} ordered by ascending version number.
     */
    List<DocumentVersionInfo> findVersionHistory(String docId);
}
