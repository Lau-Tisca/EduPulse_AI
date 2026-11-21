package com.edupulse.ingeststatic.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.edupulse.ingeststatic.repository.VectorStoreChecksumRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class InterruptedIngestionRecoveryTest {

    private VectorStoreChecksumRepository checksumRepository;
    private InterruptedIngestionRecovery recovery;

    @BeforeEach
    void setUp() {
        checksumRepository = mock(VectorStoreChecksumRepository.class);
        recovery = new InterruptedIngestionRecovery(checksumRepository);
    }

    @Test
    void resetsInterruptedClaimsAtStartup() {
        when(checksumRepository.resetInterruptedClaims()).thenReturn(1);

        recovery.releaseInterruptedClaims();

        verify(checksumRepository).resetInterruptedClaims();
    }

    @Test
    void doesNotFailStartupWhenStateTableCannotBeReached() {
        when(checksumRepository.resetInterruptedClaims())
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThatCode(recovery::releaseInterruptedClaims).doesNotThrowAnyException();
        verify(checksumRepository).resetInterruptedClaims();
    }
}
