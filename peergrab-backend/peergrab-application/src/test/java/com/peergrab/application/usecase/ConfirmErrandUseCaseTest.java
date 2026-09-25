package com.peergrab.application.usecase;

import com.peergrab.domain.errand.model.Errand;
import com.peergrab.domain.errand.model.ErrandStatus;
import com.peergrab.domain.errand.model.ErrandType;
import com.peergrab.domain.errand.ports.ErrandRepository;
import com.peergrab.domain.grab.ports.CandidateQueuePort;
import com.peergrab.domain.notify.ports.RealtimeNotifier;
import com.peergrab.shared.BizException;
import com.peergrab.shared.Money;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class ConfirmErrandUseCaseTest {

    @Test
    void confirmation_succeeds_even_if_candidate_cleanup_fails() {
        var errand = lockedErrand();
        var repository = mock(ErrandRepository.class);
        var queue = mock(CandidateQueuePort.class);
        when(repository.findById(errand.id())).thenReturn(Optional.of(errand));
        when(repository.casAccept(errand.id(), 2001L, errand.version())).thenReturn(1);
        doThrow(new IllegalStateException("Redis unavailable")).when(queue).clear(errand.id());
        var useCase = new ConfirmErrandUseCase(repository, mock(CacheEvictSupport.class),
                mock(RealtimeNotifier.class), queue);

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertDoesNotThrow(() -> useCase.confirm(new ConfirmErrandUseCase.Command(errand.id(), 2001L)));
            verify(queue, never()).clear(errand.id());
            assertDoesNotThrow(() -> TransactionSynchronizationManager.getSynchronizations()
                    .forEach(synchronization -> synchronization.afterCommit()));
            verify(queue).clear(errand.id());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void failed_cas_does_not_clear_candidates() {
        var errand = lockedErrand();
        var repository = mock(ErrandRepository.class);
        var queue = mock(CandidateQueuePort.class);
        when(repository.findById(errand.id())).thenReturn(Optional.of(errand));
        var useCase = new ConfirmErrandUseCase(repository, mock(CacheEvictSupport.class),
                mock(RealtimeNotifier.class), queue);

        assertThrows(BizException.class,
                () -> useCase.confirm(new ConfirmErrandUseCase.Command(errand.id(), 2001L)));
        verify(queue, never()).clear(errand.id());
    }

    private static Errand lockedErrand() {
        return Errand.rehydrate(10001L, 1L, 1001L, ErrandType.DELIVERY,
                "测试任务", Money.ofCents(100), 1, 2001L, ErrandStatus.LOCKED,
                1, 0, 2L, Instant.now());
    }
}
