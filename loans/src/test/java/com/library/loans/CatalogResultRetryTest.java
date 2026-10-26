package com.library.loans;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.library.loans.messaging.CatalogResultCodec;
import com.library.loans.messaging.LibraryEvent;
import com.library.loans.repository.LoanRepository;
import com.library.loans.service.CatalogResultService;

class CatalogResultRetryTest {
    @SuppressWarnings("unchecked")
    @Test void retriesOptimisticConflictsInNewTransactionsAndStopsAfterThreeAttempts() {
        var service=new CatalogResultService(mock(JdbcTemplate.class),mock(LoanRepository.class),
                Clock.systemUTC(),mock(PlatformTransactionManager.class));
        var transaction=mock(TransactionTemplate.class);
        ReflectionTestUtils.setField(service,"transaction",transaction);
        var result=mock(CatalogResultCodec.Result.class);
        when(result.event()).thenReturn(mock(LibraryEvent.class));
        when(transaction.execute(any())).thenThrow(new OptimisticLockingFailureException("conflict"))
                .thenReturn(CatalogResultService.Outcome.APPLIED);
        assertThat(service.apply(result)).isEqualTo(CatalogResultService.Outcome.APPLIED);
        verify(transaction,times(2)).execute(any());
        reset(transaction);
        when(transaction.execute(any())).thenThrow(new OptimisticLockingFailureException("conflict"));
        assertThatThrownBy(() -> service.apply(result)).isInstanceOf(OptimisticLockingFailureException.class);
        verify(transaction,times(3)).execute(any());
    }
}
