package com.peergrab.it;

import com.peergrab.application.usecase.CacheEvictSupport;
import com.peergrab.domain.errand.ports.ErrandCachePort;
import com.peergrab.infrastructure.cache.RocketMqCacheEvictAdapter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.message.MessageBuilder;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Covers the post-commit cache path used by settlement without requiring a live Broker. */
class CacheEvictionBrokerOutageTest {

    @Test
    void committedWriteReturnsAfterFirstEvictionWhileBrokerSendIsBlocked() throws Exception {
        ErrandCachePort cache = mock(ErrandCachePort.class);
        Producer producer = mock(Producer.class);
        ClientServiceProvider provider = mock(ClientServiceProvider.class);
        MessageBuilder builder = mock(MessageBuilder.class, RETURNS_SELF);
        when(provider.newMessageBuilder()).thenReturn(builder);
        when(builder.build()).thenReturn(mock(Message.class));
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
        RocketMqCacheEvictAdapter delay = new RocketMqCacheEvictAdapter(
                producer, provider, metrics, 1, 1);
        CacheEvictSupport support = new CacheEvictSupport(cache, delay, 500, true);
        var request = Executors.newSingleThreadExecutor();
        try {
            // SettleErrandUseCase invokes this after TransactionTemplate has returned.
            request.submit(() -> support.evictAfterCommit(42)).get(1, TimeUnit.SECONDS);
            verify(cache).evict(42);
            assertTrue(enteredSend.await(1, TimeUnit.SECONDS));
        } finally {
            releaseSend.countDown();
            assertTrue(finishedSend.await(1, TimeUnit.SECONDS));
            delay.stop();
            request.shutdownNow();
            metrics.close();
        }
    }
}
