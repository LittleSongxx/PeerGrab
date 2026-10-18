package com.peergrab.worker;

import com.peergrab.domain.errand.ports.LocalMessageRepository;
import com.peergrab.domain.errand.ports.DelayMessagePort;
import com.peergrab.infrastructure.config.NoopDelayMessageAdapter;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LocalMessageRetryJobTest {

    @Test
    void disabledMqDoesNotMarkUnsentRowsAsSent() {
        LocalMessageRepository repository = mock(LocalMessageRepository.class);
        new LocalMessageRetryJob(repository, new NoopDelayMessageAdapter()).retry();
        verifyNoInteractions(repository);
    }

    @Test
    void budget_exhaustion_releases_unprocessed_claims() {
        LocalMessageRepository repository = mock(LocalMessageRepository.class);
        DelayMessagePort mq = mock(DelayMessagePort.class);
        var first = new LocalMessageRepository.ClaimedMessage(
                new LocalMessageRepository.PendingMessage(1L, "timeout:1:0",
                        "topic", "{}", Instant.now(), 0), "claim-1");
        var second = new LocalMessageRepository.ClaimedMessage(
                new LocalMessageRepository.PendingMessage(2L, "timeout:2:0",
                        "topic", "{}", Instant.now(), 0), "claim-2");
        when(mq.available()).thenReturn(true);
        when(repository.claimPending(100)).thenReturn(List.of(first, second));
        AtomicInteger clockReads = new AtomicInteger();
        // 第一次读是起点 0ns，之后立即超过 10ms 预算：一条都不发，全部释放租约。
        java.util.function.LongSupplier clock = () ->
                clockReads.getAndIncrement() == 0 ? 0L : 20_000_000L;

        new LocalMessageRetryJob(repository, mq, 10, clock).retry();

        verify(mq, never()).send(anyString(), anyString(), anyString(), any(Instant.class));
        verify(repository).releaseClaim("timeout:1:0", "claim-1");
        verify(repository).releaseClaim("timeout:2:0", "claim-2");
    }

    @Test
    void failedImmediateSendCanBeRetriedAndMarkedSentByWorker() {
        LocalMessageRepository repository = mock(LocalMessageRepository.class);
        DelayMessagePort mq = mock(DelayMessagePort.class);
        var message = new LocalMessageRepository.PendingMessage(1L, "timeout:42:0",
                "errand-confirm-timeout", "{}", Instant.now().plusSeconds(300), 0);
        var claim = new LocalMessageRepository.ClaimedMessage(message, "claim-1");
        var retryClaim = new LocalMessageRepository.ClaimedMessage(message, "claim-2");
        when(mq.available()).thenReturn(true);
        when(repository.claimPending(100)).thenReturn(List.of(claim), List.of(retryClaim));
        when(repository.markClaimedSent(message.msgKey(), retryClaim.claimToken())).thenReturn(true);
        doThrow(new IllegalStateException("broker down")).doNothing().when(mq)
                .send(message.topic(), message.msgKey(), message.payload(), message.deliverAt());

        LocalMessageRetryJob job = new LocalMessageRetryJob(repository, mq);
        job.retry();
        verify(repository).markClaimedRetry(message.msgKey(), claim.claimToken(), 1000);
        verify(repository, never()).markClaimedSent(message.msgKey(), claim.claimToken());

        job.retry();
        verify(repository).markClaimedSent(message.msgKey(), retryClaim.claimToken());
    }
}
