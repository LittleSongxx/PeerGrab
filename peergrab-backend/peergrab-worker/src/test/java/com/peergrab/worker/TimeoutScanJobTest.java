package com.peergrab.worker;

import com.peergrab.application.usecase.TimeoutTransferUseCase;
import com.peergrab.domain.errand.model.Errand;
import com.peergrab.domain.errand.model.ErrandStatus;
import com.peergrab.domain.errand.model.ErrandType;
import com.peergrab.domain.errand.ports.ErrandRepository;
import com.peergrab.shared.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TimeoutScanJobTest {

    @Test
    void persisted_failure_does_not_starve_later_timeout() {
        ErrandRepository repository = mock(ErrandRepository.class);
        TimeoutTransferUseCase transfer = mock(TimeoutTransferUseCase.class);
        ScanRetryQueue retry = retryQueue();
        Instant firstAt = Instant.parse("2026-01-01T00:00:00Z");
        when(repository.findConfirmTimeoutAfter(2, null, 0, 200))
                .thenReturn(List.of(locked(1, firstAt, 0)));
        when(repository.findConfirmTimeoutAfter(2, firstAt, 1, 200))
                .thenReturn(List.of(locked(2, firstAt.plusSeconds(1), 0)));
        when(transfer.handleTimeout(1, 0)).thenThrow(new IllegalStateException("temporary failure"));
        when(transfer.handleTimeout(2, 0)).thenReturn(TimeoutTransferUseCase.Outcome.TRANSFERRED);

        TimeoutScanJob job = new TimeoutScanJob(repository, transfer, retry, 2);
        job.scan();
        job.scan();

        verify(retry).recordFailure(eq(ScanRetryQueue.Type.CONFIRM_TIMEOUT), eq(1L), eq(0), any(Exception.class));
        verify(transfer).handleTimeout(2, 0);
    }

    @Test
    void failed_retry_registration_prevents_cursor_from_passing_task() {
        ErrandRepository repository = mock(ErrandRepository.class);
        TimeoutTransferUseCase transfer = mock(TimeoutTransferUseCase.class);
        ScanRetryQueue retry = retryQueue();
        Instant firstAt = Instant.parse("2026-01-01T00:00:00Z");
        when(repository.findConfirmTimeoutAfter(2, null, 0, 200))
                .thenReturn(List.of(locked(1, firstAt, 0), locked(2, firstAt.plusSeconds(1), 0)));
        when(transfer.handleTimeout(1, 0)).thenThrow(new IllegalStateException("temporary failure"));
        doThrow(new IllegalStateException("retry table unavailable")).when(retry)
                .recordFailure(eq(ScanRetryQueue.Type.CONFIRM_TIMEOUT), eq(1L), eq(0), any(Exception.class));

        TimeoutScanJob job = new TimeoutScanJob(repository, transfer, retry, 2);
        job.scan();
        job.scan();

        verify(repository, times(2)).findConfirmTimeoutAfter(2, null, 0, 200);
        verify(transfer, never()).handleTimeout(2, 0);
    }

    @Test
    void new_worker_processes_due_retry_and_removes_stale_round() {
        ErrandRepository repository = mock(ErrandRepository.class);
        TimeoutTransferUseCase transfer = mock(TimeoutTransferUseCase.class);
        ScanRetryQueue retry = retryQueue();
        ScanRetryQueue.RetryItem current = new ScanRetryQueue.RetryItem(1, 2, 3, "lease-1");
        ScanRetryQueue.RetryItem stale = new ScanRetryQueue.RetryItem(2, 1, 3, "lease-2");
        when(retry.claimDue(ScanRetryQueue.Type.CONFIRM_TIMEOUT, 50)).thenReturn(List.of(current, stale));
        when(repository.findById(1)).thenReturn(Optional.of(locked(1, Instant.parse("2026-01-01T00:00:00Z"), 2)));
        when(repository.findById(2)).thenReturn(Optional.of(locked(2, Instant.parse("2026-01-01T00:00:00Z"), 2)));
        when(repository.findConfirmTimeoutAfter(2, null, 0, 200)).thenReturn(List.of());
        when(transfer.handleTimeout(1, 2)).thenReturn(TimeoutTransferUseCase.Outcome.TRANSFERRED);

        new TimeoutScanJob(repository, transfer, retry, 2).scan();

        verify(transfer).handleTimeout(1, 2);
        verify(transfer, never()).handleTimeout(2, 1);
        verify(retry).complete(ScanRetryQueue.Type.CONFIRM_TIMEOUT, current);
        verify(retry).complete(ScanRetryQueue.Type.CONFIRM_TIMEOUT, stale);
    }

    private static ScanRetryQueue retryQueue() {
        ScanRetryQueue retry = mock(ScanRetryQueue.class);
        when(retry.claimDue(ScanRetryQueue.Type.CONFIRM_TIMEOUT, 50)).thenReturn(List.of());
        when(retry.trackedRounds(eq(ScanRetryQueue.Type.CONFIRM_TIMEOUT), anyList())).thenReturn(Map.of());
        return retry;
    }

    private static Errand locked(long id, Instant lockedAt, int round) {
        return Errand.rehydrate(id, 1, 1001, ErrandType.DELIVERY, "task",
                Money.ofCents(100), 1, 2001L, ErrandStatus.LOCKED,
                1, round, 3, lockedAt, null, lockedAt, null);
    }
}
