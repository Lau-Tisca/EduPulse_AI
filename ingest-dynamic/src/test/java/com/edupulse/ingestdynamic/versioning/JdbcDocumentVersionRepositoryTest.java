package com.edupulse.ingestdynamic.versioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.edupulse.ingestdynamic.dto.DocumentVersionInfo;
import com.edupulse.ingestdynamic.exception.IngestionConflictException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcDocumentVersionRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private JdbcDocumentVersionRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new JdbcDocumentVersionRepository(jdbcTemplate);
    }

    @Test
    void activateVersionFencesByClaimTokenThenFlipsIsLatest() {
        when(jdbcTemplate.update(contains("AND claim_token = ?"), eq("k8s-rbac"), eq(2), eq("sha256-v2"), eq("tok-1")))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("'true'::jsonb"), eq("k8s-rbac"), eq("2"))).thenReturn(4);

        repository.activateVersion("k8s-rbac", 2, "sha256-v2", "tok-1");

        verify(jdbcTemplate).update(
                contains("SET status = 'COMPLETED'"),
                eq("k8s-rbac"),
                eq(2),
                eq("sha256-v2"),
                eq("tok-1")
        );
        verify(jdbcTemplate).update(
                contains("'false'::jsonb"),
                eq("k8s-rbac"),
                eq("2")
        );
        verify(jdbcTemplate).update(
                contains("'true'::jsonb"),
                eq("k8s-rbac"),
                eq("2")
        );
    }

    @Test
    void activateVersionRejectsStaleClaimTokenWithoutTouchingVectorStore() {
        when(jdbcTemplate.update(contains("AND claim_token = ?"), eq("k8s-rbac"), eq(2), eq("sha256-v2"), eq("stale-tok")))
                .thenReturn(0);

        assertThatThrownBy(() -> repository.activateVersion("k8s-rbac", 2, "sha256-v2", "stale-tok"))
                .isInstanceOf(IngestionConflictException.class)
                .hasMessageContaining("expired or was superseded");

        verify(jdbcTemplate, never()).update(contains("'false'::jsonb"), any(), any());
        verify(jdbcTemplate, never()).update(contains("'true'::jsonb"), any(), any());
    }

    @Test
    void activateVersionThrowsToRollBackTransactionWhenZeroTargetRowsPromoted() {
        when(jdbcTemplate.update(contains("AND claim_token = ?"), eq("k8s-rbac"), eq(2), eq("sha256-v2"), eq("tok-1")))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("'true'::jsonb"), eq("k8s-rbac"), eq("2"))).thenReturn(0);

        assertThatThrownBy(() -> repository.activateVersion("k8s-rbac", 2, "sha256-v2", "tok-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No chunks found to activate");
    }

    @Test
    void rollbackFailedVersionDeletesOnlyFailedVersionRowsWhenClaimTokenMatches() {
        when(jdbcTemplate.update(contains("SET status = 'FAILED'"), eq("k8s-rbac"), eq(2), eq("sha256-v2"), eq("tok-1")))
                .thenReturn(1);

        repository.rollbackFailedVersion("k8s-rbac", 2, "sha256-v2", "tok-1");

        verify(jdbcTemplate).update(
                contains("SET status = 'FAILED'"),
                eq("k8s-rbac"),
                eq(2),
                eq("sha256-v2"),
                eq("tok-1")
        );
        verify(jdbcTemplate).update(
                contains("AND metadata->>'version' = ?"),
                eq("k8s-rbac"),
                eq("2")
        );
    }

    @Test
    void rollbackFailedVersionDoesNotDeleteVectorsWhenClaimTokenWasSuperseded() {
        when(jdbcTemplate.update(contains("SET status = 'FAILED'"), eq("k8s-rbac"), eq(2), eq("sha256-v2"), eq("stale-tok")))
                .thenReturn(0);

        repository.rollbackFailedVersion("k8s-rbac", 2, "sha256-v2", "stale-tok");

        verify(jdbcTemplate, never()).update(contains("DELETE FROM vector_store"), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void findVersionSnapshotComputesLatestAndMaxVersionAcrossHistoryAndStateTable() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("k8s-rbac")))
                .thenReturn(List.of(
                        new DocumentVersionInfo(1, "hash-v1", false, 5),
                        new DocumentVersionInfo(2, "hash-v2", true, 6)
                ));
        // Simulate an expired/failed v3 claim recorded in dynamic_ingest_state so next attempt picks v4.
        when(jdbcTemplate.queryForObject(contains("FROM dynamic_ingest_state"), eq(Integer.class), eq("k8s-rbac")))
                .thenReturn(3);

        Optional<DocumentVersionRepository.VersionSnapshot> snapshot = repository.findVersionSnapshot("k8s-rbac");

        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().latestVersion()).isEqualTo(2);
        assertThat(snapshot.get().maxVersion()).isEqualTo(3);
        assertThat(snapshot.get().latestChecksum()).isEqualTo("hash-v2");
    }

    @Test
    @SuppressWarnings("unchecked")
    void findVersionSnapshotDoesNotTreatUnactivatedPartialV1AsLatest() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("crashed-v1")))
                .thenReturn(List.of(new DocumentVersionInfo(1, "hash-v1", false, 3)));
        when(jdbcTemplate.queryForObject(contains("FROM dynamic_ingest_state"), eq(Integer.class), eq("crashed-v1")))
                .thenReturn(1);

        Optional<DocumentVersionRepository.VersionSnapshot> snapshot = repository.findVersionSnapshot("crashed-v1");

        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().latestVersion()).isNull();
        assertThat(snapshot.get().latestChecksum()).isNull();
        assertThat(snapshot.get().maxVersion()).isEqualTo(1);
    }

    @Test
    void tryClaimReturnsFencingTokenWithStaleLeaseTtlAndEmptyWhenClaimHeld() {
        when(jdbcTemplate.queryForObject(contains("INTERVAL '15 minutes'"), eq(Boolean.class),
                eq("k8s-doc"), eq(2), eq("sha-v2"), anyString()))
                .thenReturn(Boolean.TRUE);

        Optional<String> token = repository.tryClaim("k8s-doc", 2, "sha-v2");
        assertThat(token).isPresent();
        assertThat(token.get()).isNotBlank();
        verify(jdbcTemplate).update(contains("DELETE FROM vector_store v"), eq("k8s-doc"));
        verify(jdbcTemplate).update(contains("AND metadata->>'version' = ?"), eq("k8s-doc"), eq("2"));

        when(jdbcTemplate.queryForObject(contains("INTERVAL '15 minutes'"), eq(Boolean.class),
                eq("k8s-busy"), eq(2), eq("sha-v2"), anyString()))
                .thenThrow(new EmptyResultDataAccessException(1));

        assertThat(repository.tryClaim("k8s-busy", 2, "sha-v2")).isEmpty();
    }

    @Test
    void resetInterruptedClaimsOnlyRecoversStaleLeasesOlderThan15Minutes() {
        when(jdbcTemplate.update(contains("SET status = 'FAILED'"))).thenReturn(2);

        int reset = repository.resetInterruptedClaims();

        assertThat(reset).isEqualTo(2);
        verify(jdbcTemplate, times(2)).update(contains("updated_at < CURRENT_TIMESTAMP - INTERVAL '15 minutes'"));
        verify(jdbcTemplate).update(contains("DELETE FROM vector_store v"));
        verify(jdbcTemplate).update(contains("SET status = 'FAILED'"));
    }
}
