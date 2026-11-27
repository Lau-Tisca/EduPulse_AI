package com.edupulse.ingeststatic.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcVectorStoreChecksumRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private JdbcVectorStoreChecksumRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new JdbcVectorStoreChecksumRepository(jdbcTemplate);
    }

    @Test
    void resetsOnlyClaimedRowsToFailed() {
        when(jdbcTemplate.update(argThat((String sql) -> sql != null && sql.contains("static_ingest_state"))))
                .thenReturn(2);

        int reset = repository.resetInterruptedClaims();

        assertThat(reset).isEqualTo(2);
        verify(jdbcTemplate).execute(argThat((String sql) -> sql.contains("CREATE TABLE IF NOT EXISTS static_ingest_state")));
        verify(jdbcTemplate).update(argThat((String sql) ->
                sql.contains("SET status = 'FAILED'") && sql.contains("WHERE status = 'CLAIMED'")));
    }

    @Test
    void returnsZeroAndTouchesNoVectorsWhenNothingIsInterrupted() {
        when(jdbcTemplate.update(anyString())).thenReturn(0);

        int reset = repository.resetInterruptedClaims();

        assertThat(reset).isZero();
        verify(jdbcTemplate, never()).update(argThat((String sql) -> sql.contains("vector_store")));
    }

    @Test
    void tryClaimRefusesOverwriteWhenDynamicOrActiveVersionAlreadyExists() {
        when(jdbcTemplate.queryForObject(contains("FROM vector_store v"), eq(Integer.class), eq("k8s-guide")))
                .thenReturn(1);

        assertThat(repository.hasActiveIndexedVersion("k8s-guide")).isTrue();
        assertThat(repository.tryClaim("k8s-guide", "new-sha")).isFalse();
        verify(jdbcTemplate, never()).queryForObject(contains("INSERT INTO static_ingest_state"), eq(Boolean.class), anyString(), anyString());
    }

    @Test
    void deleteByDocIdAndChecksumOnlyDeletesVersion1MatchingChecksum() {
        repository.deleteByDocIdAndChecksum("k8s-guide", "sha-v1");

        verify(jdbcTemplate).update(
                argThat((String sql) -> sql.contains("AND metadata->>'version' = '1'")
                        && sql.contains("AND metadata->>'checksum' = ?")),
                eq("k8s-guide"),
                eq("sha-v1")
        );
    }
}
