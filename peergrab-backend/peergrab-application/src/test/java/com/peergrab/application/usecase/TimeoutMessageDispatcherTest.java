package com.peergrab.application.usecase;

import com.peergrab.domain.errand.ports.DelayMessagePort;
import com.peergrab.domain.errand.ports.LocalMessageRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TimeoutMessageDispatcherTest {

    @Test
    void pausedBrokerDoesNotHoldTheCommittedGrabResponse() throws Exception {
        DelayMessagePort mq = mock(DelayMessagePort.class);
        LocalMessageRepository rows = mock(LocalMessageRepository.class);
        when(mq.available()).thenReturn(true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(mq).send(anyString(), anyString(), anyString(), any());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TimeoutMessageDispatcher dispatcher = new TimeoutMessageDispatcher(mq, rows, registry, 1, 1);
        var send = pending("timeout:42:0");
        try {
            CompletableFuture.runAsync(() -> dispatcher.dispatch(send)).get(1, TimeUnit.SECONDS);
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            verify(rows, never()).markSent(send.msgKey());
        } finally {
            release.countDown();
            verify(rows, org.mockito.Mockito.timeout(1000)).markSent(send.msgKey());
            dispatcher.stop();
            registry.close();
        }
    }

    @Test
    void failedSendRemainsPendingForWorkerRetry() throws Exception {
        DelayMessagePort mq = mock(DelayMessagePort.class);
        LocalMessageRepository rows = mock(LocalMessageRepository.class);
        when(mq.available()).thenReturn(true);
        doThrow(new IllegalStateException("broker down"))
                .when(mq).send(anyString(), anyString(), anyString(), any());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TimeoutMessageDispatcher dispatcher = new TimeoutMessageDispatcher(mq, rows, registry, 1, 1);
        var send = pending("timeout:42:0");
        try {
            dispatcher.dispatch(send);
            awaitCount(registry, "peergrab.timeout.dispatch.failed", 1);
            verify(rows, never()).markSent(anyString());
        } finally {
            dispatcher.stop();
            registry.close();
        }
    }

    @Test
    void fullQueueDefersToDurableRetryWithoutBlocking() throws Exception {
        DelayMessagePort mq = mock(DelayMessagePort.class);
        LocalMessageRepository rows = mock(LocalMessageRepository.class);
        when(mq.available()).thenReturn(true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(mq).send(anyString(), anyString(), anyString(), any());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TimeoutMessageDispatcher dispatcher = new TimeoutMessageDispatcher(mq, rows, registry, 1, 1);
        try {
            dispatcher.dispatch(pending("timeout:1:0"));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            dispatcher.dispatch(pending("timeout:2:0"));
            CompletableFuture.runAsync(() -> dispatcher.dispatch(pending("timeout:3:0")))
                    .get(1, TimeUnit.SECONDS);
            assertEquals(1, registry.counter("peergrab.timeout.dispatch.deferred").count());
            verify(rows, never()).markSent("timeout:3:0");
        } finally {
            release.countDown();
            verify(rows, org.mockito.Mockito.timeout(1000)).markSent("timeout:2:0");
            dispatcher.stop();
            registry.close();
        }
    }

    private static TimeoutTransferStep.PendingSend pending(String key) {
        return new TimeoutTransferStep.PendingSend("errand-confirm-timeout", key, "{}",
                Instant.now().plusSeconds(300));
    }

    private static void awaitCount(SimpleMeterRegistry registry, String meter, double expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (registry.counter(meter).count() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expected, registry.counter(meter).count());
    }
}
