package com.peergrab.infrastructure.cache;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.message.MessageBuilder;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RocketMqCacheEvictAdapterTest {

    @Test
    void blockedBrokerSendDoesNotHoldCommittedWriteResponse() throws Exception {
        Producer producer = mock(Producer.class);
        ClientServiceProvider provider = messageProvider();
        CountDownLatch enteredSend = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        CountDownLatch finishedSend = new CountDownLatch(1);
        when(producer.send(any(Message.class))).thenAnswer(invocation -> {
            enteredSend.countDown();
            try {
                assertTrue(releaseSend.await(5, TimeUnit.SECONDS));
                return mock(SendReceipt.class);
            } finally {
                finishedSend.countDown();
            }
        });
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        RocketMqCacheEvictAdapter adapter = new RocketMqCacheEvictAdapter(
                producer, provider, metrics, 1, 1);
        var request = Executors.newSingleThreadExecutor();
        try {
            // This is the post-commit call made by CacheEvictSupport after its first Redis deletion.
            request.submit(() -> adapter.scheduleEvict(42, Instant.now().plusMillis(500)))
                    .get(1, TimeUnit.SECONDS);
            assertTrue(enteredSend.await(1, TimeUnit.SECONDS));
            assertEquals(0, metrics.counter("peergrab.cache.double_delete.dropped").count());
        } finally {
            releaseSend.countDown();
            assertTrue(finishedSend.await(1, TimeUnit.SECONDS));
            adapter.stop();
            request.shutdownNow();
            metrics.close();
        }
    }

    @Test
    void fullQueueDropsOnlyBestEffortSecondDeletionAndCountsIt() throws Exception {
        Producer producer = mock(Producer.class);
        CountDownLatch enteredSend = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        CountDownLatch bothFinished = new CountDownLatch(2);
        when(producer.send(any(Message.class))).thenAnswer(invocation -> {
            enteredSend.countDown();
            try {
                assertTrue(releaseSend.await(5, TimeUnit.SECONDS));
                return mock(SendReceipt.class);
            } finally {
                bothFinished.countDown();
            }
        });
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        RocketMqCacheEvictAdapter adapter = new RocketMqCacheEvictAdapter(
                producer, messageProvider(), metrics, 1, 1);
        try {
            adapter.scheduleEvict(1, Instant.now().plusMillis(500));
            assertTrue(enteredSend.await(1, TimeUnit.SECONDS));
            adapter.scheduleEvict(2, Instant.now().plusMillis(500)); // queued
            adapter.scheduleEvict(3, Instant.now().plusMillis(500)); // rejected without blocking
            assertEquals(1, metrics.counter("peergrab.cache.double_delete.dropped").count());
        } finally {
            releaseSend.countDown();
            assertTrue(bothFinished.await(1, TimeUnit.SECONDS));
            adapter.stop();
            metrics.close();
        }
    }

    @Test
    void failedBrokerSendIsCountedWithoutEscapingSenderThread() throws Exception {
        Producer producer = mock(Producer.class);
        when(producer.send(any(Message.class))).thenThrow(new IllegalStateException("broker down"));
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        RocketMqCacheEvictAdapter adapter = new RocketMqCacheEvictAdapter(
                producer, messageProvider(), metrics, 1, 1);
        try {
            adapter.scheduleEvict(42, Instant.now().plusMillis(500));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (metrics.counter("peergrab.cache.double_delete.send.failed").count() == 0
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(1, metrics.counter("peergrab.cache.double_delete.send.failed").count());
        } finally {
            adapter.stop();
            metrics.close();
        }
    }

    private ClientServiceProvider messageProvider() {
        ClientServiceProvider provider = mock(ClientServiceProvider.class);
        MessageBuilder builder = mock(MessageBuilder.class, RETURNS_SELF);
        when(provider.newMessageBuilder()).thenReturn(builder);
        when(builder.build()).thenReturn(mock(Message.class));
        return provider;
    }
}
