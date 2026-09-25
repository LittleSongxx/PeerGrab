package com.peergrab.application.usecase;

import com.peergrab.domain.credit.ports.CreditRepository;
import com.peergrab.domain.errand.model.Errand;
import com.peergrab.domain.errand.model.ErrandStatus;
import com.peergrab.domain.errand.model.ErrandType;
import com.peergrab.domain.errand.ports.DelayMessagePort;
import com.peergrab.domain.errand.ports.ErrandQueryPort;
import com.peergrab.domain.errand.ports.ErrandRepository;
import com.peergrab.domain.errand.ports.LocalMessageRepository;
import com.peergrab.domain.grab.ports.CandidateQueuePort;
import com.peergrab.domain.grab.ports.GrabSlotPort;
import com.peergrab.shared.Money;
import com.peergrab.shared.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TimeoutTransferUseCaseTest {

    @Test
    void skips_candidate_whose_credit_fell_below_threshold() {
        var fixture = new Fixture();
        when(fixture.queue.pollBest(10001L)).thenReturn(
                Optional.of(new CandidateQueuePort.Candidate(2002L, 10.0)),
                Optional.of(new CandidateQueuePort.Candidate(2003L, 20.0)));
        when(fixture.credit.scoreOf(2002L)).thenReturn(20);
        when(fixture.credit.scoreOf(2003L)).thenReturn(60);
        when(fixture.step.transfer(fixture.errand, 2003L, 300L))
                .thenReturn(new TimeoutTransferStep.StepResult(true, null));

        assertEquals(TimeoutTransferUseCase.Outcome.TRANSFERRED,
                fixture.useCase.handleTimeout(10001L, 0));
        verify(fixture.step, never()).transfer(fixture.errand, 2002L, 300L);
        verify(fixture.step).transfer(fixture.errand, 2003L, 300L);
    }

    @Test
    void failed_cas_restores_candidate_with_original_score() {
        var fixture = new Fixture();
        when(fixture.queue.pollBest(10001L)).thenReturn(
                Optional.of(new CandidateQueuePort.Candidate(2002L, 12.5)));
        when(fixture.credit.scoreOf(2002L)).thenReturn(60);
        when(fixture.step.transfer(fixture.errand, 2002L, 300L))
                .thenReturn(TimeoutTransferStep.StepResult.skipped());

        assertEquals(TimeoutTransferUseCase.Outcome.SKIPPED,
                fixture.useCase.handleTimeout(10001L, 0));
        verify(fixture.queue).offer(10001L, 2002L, 12.5);
    }

    @Test
    void revert_invalidates_old_slot_and_candidate_queue() {
        var fixture = new Fixture();
        when(fixture.queue.pollBest(10001L)).thenReturn(Optional.empty());
        when(fixture.step.revert(fixture.errand))
                .thenReturn(new TimeoutTransferStep.StepResult(true, null));

        assertEquals(TimeoutTransferUseCase.Outcome.REVERTED,
                fixture.useCase.handleTimeout(10001L, 0));
        verify(fixture.slot).invalidate(10001L);
        verify(fixture.queue).clear(10001L);
        verify(fixture.slot, never()).rollback(anyLong(), anyLong(), anyString());
    }

    private static final class Fixture {
        final Errand errand = Errand.rehydrate(10001L, 1L, 1001L, ErrandType.DELIVERY,
                "测试任务", Money.ofCents(100), 1, 2001L, ErrandStatus.LOCKED,
                1, 0, 2L, Instant.now());
        final ErrandRepository errands = mock(ErrandRepository.class);
        final CandidateQueuePort queue = mock(CandidateQueuePort.class);
        final GrabSlotPort slot = mock(GrabSlotPort.class);
        final TimeoutTransferStep step = mock(TimeoutTransferStep.class);
        final CreditRepository credit = mock(CreditRepository.class);
        final ErrandQueryPort queries = mock(ErrandQueryPort.class);
        final TimeoutTransferUseCase useCase;

        Fixture() {
            when(errands.findById(10001L)).thenReturn(Optional.of(errand));
            useCase = new TimeoutTransferUseCase(errands, queue, slot,
                    mock(DelayMessagePort.class), mock(LocalMessageRepository.class), step,
                    mock(CacheEvictSupport.class), credit, queries, new SnowflakeIdGenerator(1),
                    40, 5, 300L, 5);
        }
    }
}
