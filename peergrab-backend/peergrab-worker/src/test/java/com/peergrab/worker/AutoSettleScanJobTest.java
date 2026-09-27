package com.peergrab.worker;

import com.peergrab.application.usecase.SettleErrandUseCase;
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

import static org.mockito.Mockito.*;

class AutoSettleScanJobTest {

    @Test
    void a_failing_old_task_does_not_starve_later_due_tasks() {
        ErrandRepository repository = mock(ErrandRepository.class);
        SettleErrandUseCase settle = mock(SettleErrandUseCase.class);
        ScanRetryQueue retry = mock(ScanRetryQueue.class);
        when(retry.claimDue(ScanRetryQueue.Type.AUTO_SETTLE, 50)).thenReturn(List.of());
        when(retry.trackedRounds(eq(ScanRetryQueue.Type.AUTO_SETTLE), anyList())).thenReturn(Map.of());
        Instant firstTime = Instant.parse("2026-01-01T00:00:00Z");
        Instant secondTime = firstTime.plusSeconds(1);
        when(repository.findAutoSettleDueAfter(0, null, 0, 200))
                .thenReturn(List.of(errand(1, firstTime)));
        when(repository.findAutoSettleDueAfter(0, firstTime, 1, 200))
                .thenReturn(List.of(errand(2, secondTime)));
        when(settle.settle(1, Errand.SYSTEM_OPERATOR))
                .thenThrow(new IllegalStateException("temporary failure"));

        AutoSettleScanJob job = new AutoSettleScanJob(repository, settle, retry);
        job.scan();
        job.scan();

        verify(settle).settle(1, Errand.SYSTEM_OPERATOR);
        verify(settle).settle(2, Errand.SYSTEM_OPERATOR);
        verify(retry).recordFailure(eq(ScanRetryQueue.Type.AUTO_SETTLE), eq(1L), eq(0), any(Exception.class));
    }

    @Test
    void failed_retry_registration_keeps_cursor_before_failed_task() {
        ErrandRepository repository = mock(ErrandRepository.class);
        SettleErrandUseCase settle = mock(SettleErrandUseCase.class);
        ScanRetryQueue retry = mock(ScanRetryQueue.class);
        Instant firstTime = Instant.parse("2026-01-01T00:00:00Z");
        when(retry.claimDue(ScanRetryQueue.Type.AUTO_SETTLE, 50)).thenReturn(List.of());
        when(retry.trackedRounds(eq(ScanRetryQueue.Type.AUTO_SETTLE), anyList())).thenReturn(Map.of());
        when(repository.findAutoSettleDueAfter(0, null, 0, 200))
                .thenReturn(List.of(errand(1, firstTime), errand(2, firstTime.plusSeconds(1))));
        when(settle.settle(1, Errand.SYSTEM_OPERATOR))
                .thenThrow(new IllegalStateException("temporary failure"));
        doThrow(new IllegalStateException("retry table unavailable")).when(retry)
                .recordFailure(eq(ScanRetryQueue.Type.AUTO_SETTLE), eq(1L), eq(0), any(Exception.class));

        AutoSettleScanJob job = new AutoSettleScanJob(repository, settle, retry);
        job.scan();
        job.scan();

        verify(repository, times(2)).findAutoSettleDueAfter(0, null, 0, 200);
        verify(settle, never()).settle(2, Errand.SYSTEM_OPERATOR);
    }

    @Test
    void due_retry_is_processed_after_worker_recreation() {
        ErrandRepository repository = mock(ErrandRepository.class);
        SettleErrandUseCase settle = mock(SettleErrandUseCase.class);
        ScanRetryQueue retry = mock(ScanRetryQueue.class);
        ScanRetryQueue.RetryItem item = new ScanRetryQueue.RetryItem(1, 0, 2, "lease-token");
        when(retry.claimDue(ScanRetryQueue.Type.AUTO_SETTLE, 50)).thenReturn(List.of(item));
        when(repository.findById(1)).thenReturn(Optional.of(errand(1, Instant.parse("2026-01-01T00:00:00Z"))));
        when(repository.findAutoSettleDueAfter(0, null, 0, 200)).thenReturn(List.of());
        when(retry.trackedRounds(eq(ScanRetryQueue.Type.AUTO_SETTLE), anyList())).thenReturn(Map.of());

        new AutoSettleScanJob(repository, settle, retry).scan();

        verify(settle).settle(1, Errand.SYSTEM_OPERATOR);
        verify(retry).complete(ScanRetryQueue.Type.AUTO_SETTLE, item);
    }

    @Test
    void failing_due_retry_is_rescheduled_without_blocking_fresh_scan() {
        ErrandRepository repository = mock(ErrandRepository.class);
        SettleErrandUseCase settle = mock(SettleErrandUseCase.class);
        ScanRetryQueue retry = mock(ScanRetryQueue.class);
        ScanRetryQueue.RetryItem item = new ScanRetryQueue.RetryItem(1, 0, 2, "lease-token");
        Instant firstTime = Instant.parse("2026-01-01T00:00:00Z");
        when(retry.claimDue(ScanRetryQueue.Type.AUTO_SETTLE, 50)).thenReturn(List.of(item));
        when(repository.findById(1)).thenReturn(Optional.of(errand(1, firstTime)));
        when(repository.findAutoSettleDueAfter(0, null, 0, 200))
                .thenReturn(List.of(errand(2, firstTime.plusSeconds(1))));
        when(retry.trackedRounds(eq(ScanRetryQueue.Type.AUTO_SETTLE), anyList())).thenReturn(Map.of());
        when(settle.settle(1, Errand.SYSTEM_OPERATOR))
                .thenThrow(new IllegalStateException("temporary failure"));

        new AutoSettleScanJob(repository, settle, retry).scan();

        verify(retry).reschedule(eq(ScanRetryQueue.Type.AUTO_SETTLE), eq(item), any(Exception.class));
        verify(settle).settle(2, Errand.SYSTEM_OPERATOR);
    }

    private static Errand errand(long id, Instant deliveredAt) {
        return Errand.rehydrate(id, 1, 1001, ErrandType.DELIVERY, "task",
                Money.ofCents(100), 1, 2001L, ErrandStatus.DELIVERED,
                1, 0, 3, null, deliveredAt, null, deliveredAt);
    }
}
