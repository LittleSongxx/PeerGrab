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

import static org.mockito.Mockito.*;

class AutoSettleScanJobTest {

    @Test
    void a_failing_old_task_does_not_starve_later_due_tasks() {
        ErrandRepository repository = mock(ErrandRepository.class);
        SettleErrandUseCase settle = mock(SettleErrandUseCase.class);
        Instant firstTime = Instant.parse("2026-01-01T00:00:00Z");
        Instant secondTime = firstTime.plusSeconds(1);
        when(repository.findAutoSettleDueAfter(86400, null, 0, 200))
                .thenReturn(List.of(errand(1, firstTime)));
        when(repository.findAutoSettleDueAfter(86400, firstTime, 1, 200))
                .thenReturn(List.of(errand(2, secondTime)));
        when(settle.settle(1, Errand.SYSTEM_OPERATOR))
                .thenThrow(new IllegalStateException("temporary failure"));

        AutoSettleScanJob job = new AutoSettleScanJob(repository, settle, 86400);
        job.scan();
        job.scan();

        verify(settle).settle(1, Errand.SYSTEM_OPERATOR);
        verify(settle).settle(2, Errand.SYSTEM_OPERATOR);
    }

    private static Errand errand(long id, Instant deliveredAt) {
        return Errand.rehydrate(id, 1, 1001, ErrandType.DELIVERY, "task",
                Money.ofCents(100), 1, 2001L, ErrandStatus.DELIVERED,
                1, 0, 3, null, deliveredAt);
    }
}
