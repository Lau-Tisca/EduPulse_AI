package com.edupulse.ingestdynamic.versioning;

import static org.assertj.core.api.Assertions.assertThat;
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
}
