package com.edupulse.ingeststatic.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
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
}
