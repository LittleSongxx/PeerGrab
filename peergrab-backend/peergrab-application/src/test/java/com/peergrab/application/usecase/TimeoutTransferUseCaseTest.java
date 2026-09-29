package com.peergrab.application.usecase;

import com.peergrab.domain.credit.ports.CreditRepository;
import com.peergrab.domain.errand.model.Errand;
import com.peergrab.domain.errand.model.ErrandStatus;
import com.peergrab.domain.errand.model.ErrandType;
import com.peergrab.domain.errand.ports.ErrandQueryPort;
import com.peergrab.domain.errand.ports.ErrandRepository;
import com.peergrab.domain.grab.ports.CandidateQueuePort;
import com.peergrab.domain.grab.ports.GrabSlotPort;
import com.peergrab.shared.Money;
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
        var first = new CandidateQueuePort.Candidate(2002L, 10.0, "lease-1");
        var second = new CandidateQueuePort.Candidate(2003L, 20.0, "lease-2");
        when(fixture.queue.pollBest(10001L)).thenReturn(
                Optional.of(first), Optional.of(second));
        when(fixture.credit.scoreOf(2002L)).thenReturn(20);
        when(fixture.credit.scoreOf(2003L)).thenReturn(60);
        when(fixture.step.transfer(fixture.errand, 2003L))
                .thenReturn(new TimeoutTransferStep.StepResult(true, null));

        assertEquals(TimeoutTransferUseCase.Outcome.TRANSFERRED,
                fixture.useCase.handleTimeout(10001L, 0));
        verify(fixture.step, never()).transfer(fixture.errand, 2002L);
        verify(fixture.step).transfer(fixture.errand, 2003L);
        verify(fixture.queue).acknowledge(10001L, first);
        verify(fixture.queue).acknowledge(10001L, second);
        verify(fixture.cache).evictAfterCommit(10001L);
    }

    @Test
    void early_message_does_not_take_a_candidate() {
        var fixture = new Fixture();
        when(fixture.errands.confirmTimeoutDue(10001L, 0)).thenReturn(false);

        assertEquals(TimeoutTransferUseCase.Outcome.SKIPPED,
                fixture.useCase.handleTimeout(10001L, 0));
        verifyNoInteractions(fixture.queue);
    }

    @Test
    void failed_cas_restores_candidate_with_original_score() {
        var fixture = new Fixture();
        var candidate = new CandidateQueuePort.Candidate(2002L, 12.5, "lease-1");
        when(fixture.queue.pollBest(10001L)).thenReturn(
                Optional.of(candidate));
        when(fixture.credit.scoreOf(2002L)).thenReturn(60);
        when(fixture.step.transfer(fixture.errand, 2002L))
                .thenReturn(TimeoutTransferStep.StepResult.skipped());

        assertEquals(TimeoutTransferUseCase.Outcome.SKIPPED,
                fixture.useCase.handleTimeout(10001L, 0));
        verify(fixture.queue).release(10001L, candidate);
    }

    @Test
    void leased_candidate_prevents_premature_revert() {
        var fixture = new Fixture();
        when(fixture.queue.pollBest(10001L)).thenReturn(Optional.empty());
        when(fixture.queue.size(10001L)).thenReturn(1L);

        assertEquals(TimeoutTransferUseCase.Outcome.SKIPPED,
                fixture.useCase.handleTimeout(10001L, 0));
        verify(fixture.step, never()).revert(any());
    }

    @Test
    void transaction_quota_rejection_discards_candidate_and_tries_next() {
        var fixture = new Fixture();
        var first = new CandidateQueuePort.Candidate(2002L, 10.0, "lease-1");
        var second = new CandidateQueuePort.Candidate(2003L, 20.0, "lease-2");
        when(fixture.queue.pollBest(10001L)).thenReturn(Optional.of(first), Optional.of(second));
        when(fixture.credit.scoreOf(2002L)).thenReturn(60);
        when(fixture.credit.scoreOf(2003L)).thenReturn(60);
        when(fixture.step.transfer(fixture.errand, 2002L))
                .thenReturn(TimeoutTransferStep.StepResult.capacityRejected());
        when(fixture.step.transfer(fixture.errand, 2003L))
                .thenReturn(new TimeoutTransferStep.StepResult(true, null));

        assertEquals(TimeoutTransferUseCase.Outcome.TRANSFERRED,
                fixture.useCase.handleTimeout(10001L, 0));
        verify(fixture.queue).acknowledge(10001L, first);
        verify(fixture.queue).acknowledge(10001L, second);
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

    @Test
    void firstTimeoutIsCommittedBeforeBackgroundDispatch() {
        var fixture = new Fixture();
        var send = new TimeoutTransferStep.PendingSend("errand-confirm-timeout",
                "timeout:10001:0", "{}", Instant.now().plusSeconds(300));
        when(fixture.step.enqueueTimeout(10001L, 0, 2L)).thenReturn(send);

        fixture.useCase.scheduleFirstTimeout(10001L, 0, 2L);

        var order = inOrder(fixture.step, fixture.dispatcher);
        order.verify(fixture.step).enqueueTimeout(10001L, 0, 2L);
        order.verify(fixture.dispatcher).dispatch(send);
    }

    private static final class Fixture {
        final Errand errand = Errand.rehydrate(10001L, 1L, 1001L, ErrandType.DELIVERY,
                "测试任务", Money.ofCents(100), 1, 2001L, ErrandStatus.LOCKED,
                1, 0, 2L, Instant.now());
        final ErrandRepository errands = mock(ErrandRepository.class);
        final CandidateQueuePort queue = mock(CandidateQueuePort.class);
        final GrabSlotPort slot = mock(GrabSlotPort.class);
        final TimeoutTransferStep step = mock(TimeoutTransferStep.class);
        final TimeoutMessageDispatcher dispatcher = mock(TimeoutMessageDispatcher.class);
        final CreditRepository credit = mock(CreditRepository.class);
        final CacheEvictSupport cache = mock(CacheEvictSupport.class);
        final ErrandQueryPort queries = mock(ErrandQueryPort.class);
        final TimeoutTransferUseCase useCase;

        Fixture() {
            when(errands.findById(10001L)).thenReturn(Optional.of(errand));
            when(errands.confirmTimeoutDue(10001L, 0)).thenReturn(true);
            useCase = new TimeoutTransferUseCase(errands, queue, slot,
                    dispatcher, step,
                    cache, credit, queries,
                    40, 5, 5);
        }
    }
}
