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
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;

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

        TimeoutScanJob job = new TimeoutScanJob(repository, transfer, retry, 2, 5, 10_000);
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

        TimeoutScanJob job = new TimeoutScanJob(repository, transfer, retry, 2, 5, 10_000);
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

        new TimeoutScanJob(repository, transfer, retry, 2, 5, 10_000).scan();

        verify(transfer).handleTimeout(1, 2);
        verify(transfer, never()).handleTimeout(2, 1);
        verify(retry).complete(ScanRetryQueue.Type.CONFIRM_TIMEOUT, current);
        verify(retry).complete(ScanRetryQueue.Type.CONFIRM_TIMEOUT, stale);
    }

    @Test
    void drains_several_full_pages_without_waiting_for_the_next_schedule() {
        ErrandRepository repository = mock(ErrandRepository.class);
        TimeoutTransferUseCase transfer = mock(TimeoutTransferUseCase.class);
        ScanRetryQueue retry = retryQueue();
        Instant deadline = Instant.parse("2026-01-01T00:00:00Z");
        when(repository.findConfirmTimeoutAfter(2, null, 0, 200))
                .thenReturn(page(1, 200, deadline));
        when(repository.findConfirmTimeoutAfter(2, deadline, 200, 200))
                .thenReturn(page(201, 400, deadline));
        when(repository.findConfirmTimeoutAfter(2, deadline, 400, 200))
                .thenReturn(List.of());
        when(transfer.handleTimeout(anyLong(), eq(0)))
                .thenReturn(TimeoutTransferUseCase.Outcome.REVERTED);

        new TimeoutScanJob(repository, transfer, retry, 2, 5, 10_000).scan();

        verify(transfer, times(400)).handleTimeout(anyLong(), eq(0));
        verify(repository).findConfirmTimeoutAfter(2, deadline, 200, 200);
        verify(repository).findConfirmTimeoutAfter(2, deadline, 400, 200);
    }

    @Test
    void batch_limit_preserves_cursor_for_next_schedule() {
        ErrandRepository repository = mock(ErrandRepository.class);
        TimeoutTransferUseCase transfer = mock(TimeoutTransferUseCase.class);
        ScanRetryQueue retry = retryQueue();
        Instant deadline = Instant.parse("2026-01-01T00:00:00Z");
        when(repository.findConfirmTimeoutAfter(2, null, 0, 200))
                .thenReturn(page(1, 200, deadline));
        when(repository.findConfirmTimeoutAfter(2, deadline, 200, 200))
                .thenReturn(List.of(locked(201, deadline, 0)));
        TimeoutScanJob job = new TimeoutScanJob(repository, transfer, retry, 2, 1, 10_000);

        job.scan();
        verify(transfer, never()).handleTimeout(201, 0);
        job.scan();

        verify(transfer).handleTimeout(201, 0);
        verify(repository).findConfirmTimeoutAfter(2, deadline, 200, 200);
    }

    @Test
    void skipped_full_page_is_not_replayed_within_one_run_but_is_revisited_next_run() {
        ErrandRepository repository = mock(ErrandRepository.class);
        TimeoutTransferUseCase transfer = mock(TimeoutTransferUseCase.class);
        ScanRetryQueue retry = retryQueue();
        Instant deadline = Instant.parse("2026-01-01T00:00:00Z");
        when(repository.findConfirmTimeoutAfter(2, null, 0, 200))
                .thenReturn(page(1, 200, deadline));
        when(repository.findConfirmTimeoutAfter(2, deadline, 200, 200))
                .thenReturn(List.of());
        when(transfer.handleTimeout(anyLong(), eq(0)))
                .thenReturn(TimeoutTransferUseCase.Outcome.SKIPPED);
        TimeoutScanJob job = new TimeoutScanJob(repository, transfer, retry, 2, 5, 10_000);

        job.scan();
        verify(transfer, times(200)).handleTimeout(anyLong(), eq(0));
        verify(repository).findConfirmTimeoutAfter(2, null, 0, 200);
        job.scan();

        verify(transfer, times(400)).handleTimeout(anyLong(), eq(0));
        verify(repository, times(2)).findConfirmTimeoutAfter(2, null, 0, 200);
    }

    @Test
    void time_budget_stops_mid_page_and_resumes_at_first_unprocessed_item() {
        ErrandRepository repository = mock(ErrandRepository.class);
        TimeoutTransferUseCase transfer = mock(TimeoutTransferUseCase.class);
        ScanRetryQueue retry = retryQueue();
        Instant deadline = Instant.parse("2026-01-01T00:00:00Z");
        AtomicLong clock = new AtomicLong();
        when(repository.findConfirmTimeoutAfter(2, null, 0, 200))
                .thenReturn(page(1, 3, deadline));
        when(repository.findConfirmTimeoutAfter(2, deadline, 2, 200))
                .thenReturn(List.of(locked(3, deadline, 0)));
        when(transfer.handleTimeout(anyLong(), eq(0))).thenAnswer(invocation -> {
            clock.addAndGet(2_000_000);
            return TimeoutTransferUseCase.Outcome.REVERTED;
        });
        TimeoutScanJob job = new TimeoutScanJob(repository, transfer, retry,
                2, 5, 3, clock::get);

        job.scan();
        verify(transfer, never()).handleTimeout(3, 0);
        job.scan();

        verify(transfer).handleTimeout(3, 0);
        verify(repository).findConfirmTimeoutAfter(2, deadline, 2, 200);
    }

    private static ScanRetryQueue retryQueue() {
        ScanRetryQueue retry = mock(ScanRetryQueue.class);
        when(retry.claimDue(ScanRetryQueue.Type.CONFIRM_TIMEOUT, 50)).thenReturn(List.of());
        when(retry.trackedRounds(eq(ScanRetryQueue.Type.CONFIRM_TIMEOUT), anyList())).thenReturn(Map.of());
        return retry;
    }

    private static List<Errand> page(long firstId, long lastId, Instant deadline) {
        return LongStream.rangeClosed(firstId, lastId)
                .mapToObj(id -> locked(id, deadline, 0)).toList();
    }

    private static Errand locked(long id, Instant lockedAt, int round) {
        return Errand.rehydrate(id, 1, 1001, ErrandType.DELIVERY, "task",
                Money.ofCents(100), 1, 2001L, ErrandStatus.LOCKED,
                1, round, 3, lockedAt, null, lockedAt, null);
    }
}
