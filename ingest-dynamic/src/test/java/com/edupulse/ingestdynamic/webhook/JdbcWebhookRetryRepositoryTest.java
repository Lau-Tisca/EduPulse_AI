package com.edupulse.ingestdynamic.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.edupulse.ingestdynamic.webhook.WebhookRetryRepository.WebhookJob;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcWebhookRetryRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private JdbcWebhookRetryRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new JdbcWebhookRetryRepository(jdbcTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void claimJobIssuesLeaseTokenAndReclaimsOnlyStaleProcessingRows() {
        WebhookJob expected = new WebhookJob(
                7L, "https://kubernetes.io/docs/", "k8s", "PROCESSING", 0, 3, Instant.now(), null, "lease-7");
        when(jdbcTemplate.queryForObject(
                contains("lease_token = ?"),
                any(RowMapper.class),
                anyString(),
                eq(7L)))
                .thenReturn(expected);

        Optional<WebhookJob> claimed = repository.claimJob(7L);

        assertThat(claimed).contains(expected);
        verify(jdbcTemplate).queryForObject(
                contains("INTERVAL '15 minutes'"),
                any(RowMapper.class),
                anyString(),
                eq(7L)
        );
    }

    @Test
    void terminalUpdatesFenceByProcessingStatusAndLeaseToken() {
        Instant nextRetry = Instant.now().plusSeconds(30);
        when(jdbcTemplate.update(contains("AND lease_token = ?"), eq(1L), eq("lease-1"))).thenReturn(1);
        when(jdbcTemplate.update(contains("AND lease_token = ?"), eq(1), any(Timestamp.class), eq("err"), eq(2L), eq("lease-2")))
                .thenReturn(0);
        when(jdbcTemplate.update(contains("AND lease_token = ?"), eq(3), eq("fatal"), eq(3L), eq("lease-3")))
                .thenReturn(1);

        repository.markCompleted(1L, "lease-1");
        repository.scheduleRetry(2L, "lease-2", 1, nextRetry, "err");
        repository.markDeadLetter(3L, "lease-3", 3, "fatal");

        verify(jdbcTemplate).update(
                contains("AND status = 'PROCESSING'"),
                eq(1L),
                eq("lease-1")
        );
        verify(jdbcTemplate).update(
                contains("AND status = 'PROCESSING'"),
                eq(1),
                eq(Timestamp.from(nextRetry)),
                eq("err"),
                eq(2L),
                eq("lease-2")
        );
        verify(jdbcTemplate).update(
                contains("AND status = 'PROCESSING'"),
                eq(3),
                eq("fatal"),
                eq(3L),
                eq("lease-3")
        );
    }

    @Test
    void resetStuckProcessingOnlyRequeuesLeasesOlderThan15Minutes() {
        when(jdbcTemplate.update(contains("INTERVAL '15 minutes'"))).thenReturn(2);

        int reset = repository.resetStuckProcessing();

        assertThat(reset).isEqualTo(2);
        verify(jdbcTemplate).update(contains("WHERE status = 'PROCESSING'\n  AND updated_at < CURRENT_TIMESTAMP - INTERVAL '15 minutes'"));
    }
}
