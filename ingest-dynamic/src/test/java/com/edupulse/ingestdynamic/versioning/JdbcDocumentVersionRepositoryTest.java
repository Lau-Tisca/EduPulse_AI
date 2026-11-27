package com.edupulse.ingestdynamic.versioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.edupulse.ingestdynamic.dto.DocumentVersionInfo;
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
    void activateVersionFlipsOldLatestToFalseAndPromotesNewVersionToTrue() {
        when(jdbcTemplate.update(contains("'true'::jsonb"), eq("k8s-rbac"), eq("2"))).thenReturn(4);

        repository.activateVersion("k8s-rbac", 2, "sha256-v2");

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
        verify(jdbcTemplate).update(
                contains("SET status = 'COMPLETED'"),
                eq("k8s-rbac"),
                eq(2),
                eq("sha256-v2")
        );
    }

    @Test
    void activateVersionThrowsToRollBackTransactionWhenZeroTargetRowsPromoted() {
        when(jdbcTemplate.update(contains("'true'::jsonb"), eq("k8s-rbac"), eq("2"))).thenReturn(0);

        assertThatThrownBy(() -> repository.activateVersion("k8s-rbac", 2, "sha256-v2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No chunks found to activate");
    }

    @Test
    void rollbackFailedVersionDeletesOnlyFailedVersionRows() {
        repository.rollbackFailedVersion("k8s-rbac", 2, "sha256-v2");

        verify(jdbcTemplate).update(
                contains("AND metadata->>'version' = ?"),
                eq("k8s-rbac"),
                eq("2")
        );
        verify(jdbcTemplate).update(
                contains("SET status = 'FAILED'"),
                eq("k8s-rbac"),
                eq(2),
                eq("sha256-v2")
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void findVersionSnapshotComputesLatestAndMaxVersionAcrossHistory() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("k8s-rbac")))
                .thenReturn(List.of(
                        new DocumentVersionInfo(1, "hash-v1", false, 5),
                        new DocumentVersionInfo(2, "hash-v2", true, 6)
                ));

        Optional<DocumentVersionRepository.VersionSnapshot> snapshot = repository.findVersionSnapshot("k8s-rbac");

        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().latestVersion()).isEqualTo(2);
        assertThat(snapshot.get().maxVersion()).isEqualTo(2);
        assertThat(snapshot.get().latestChecksum()).isEqualTo("hash-v2");
    }

    @Test
    @SuppressWarnings("unchecked")
    void findVersionSnapshotDoesNotTreatUnactivatedPartialV1AsLatest() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("crashed-v1")))
                .thenReturn(List.of(new DocumentVersionInfo(1, "hash-v1", false, 3)));

        Optional<DocumentVersionRepository.VersionSnapshot> snapshot = repository.findVersionSnapshot("crashed-v1");

        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().latestVersion()).isNull();
        assertThat(snapshot.get().latestChecksum()).isNull();
        assertThat(snapshot.get().maxVersion()).isEqualTo(1);
    }

    @Test
    void tryClaimUsesStaleLeaseTtlAndReturnsFalseWhenClaimHeld() {
        when(jdbcTemplate.queryForObject(contains("INTERVAL '15 minutes'"), eq(Boolean.class),
                eq("k8s-doc"), eq(2), eq("sha-v2")))
                .thenReturn(Boolean.TRUE);

        assertThat(repository.tryClaim("k8s-doc", 2, "sha-v2")).isTrue();
        verify(jdbcTemplate).update(contains("DELETE FROM vector_store v"), eq("k8s-doc"));
        verify(jdbcTemplate).update(contains("AND metadata->>'version' = ?"), eq("k8s-doc"), eq("2"));

        when(jdbcTemplate.queryForObject(contains("INTERVAL '15 minutes'"), eq(Boolean.class),
                eq("k8s-busy"), eq(2), eq("sha-v2")))
                .thenThrow(new EmptyResultDataAccessException(1));

        assertThat(repository.tryClaim("k8s-busy", 2, "sha-v2")).isFalse();
    }

    @Test
    void resetInterruptedClaimsDeletesUnactivatedPartialChunksBeforeMarkingFailed() {
        when(jdbcTemplate.update(contains("SET status = 'FAILED'"))).thenReturn(2);

        int reset = repository.resetInterruptedClaims();

        assertThat(reset).isEqualTo(2);
        verify(jdbcTemplate).update(contains("AND v.metadata->>'is_latest' = 'false'"));
        verify(jdbcTemplate).update(contains("WHERE status = 'CLAIMED'"));
    }
}
