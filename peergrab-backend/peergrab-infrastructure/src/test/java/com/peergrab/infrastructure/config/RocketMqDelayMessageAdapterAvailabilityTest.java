package com.peergrab.infrastructure.config;

import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.message.MessageBuilder;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RocketMqDelayMessageAdapterAvailabilityTest {

    @Test
    @DisplayName("发送失败后进入冷却窗口，冷却结束恢复可用")
    void failureEntersCooldownThenRecovers() throws Exception {
        ClientServiceProvider provider = mock(ClientServiceProvider.class);
        Producer producer = mock(Producer.class);
        MessageBuilder builder = mock(MessageBuilder.class);
        Message message = mock(Message.class);
        when(provider.newMessageBuilder()).thenReturn(builder);
        when(builder.setTopic(anyString())).thenReturn(builder);
        when(builder.setKeys(anyString())).thenReturn(builder);
        when(builder.setBody(any(byte[].class))).thenReturn(builder);
        when(builder.setDeliveryTimestamp(anyLong())).thenReturn(builder);
        when(builder.build()).thenReturn(message);
        when(producer.send(message)).thenThrow(new IllegalStateException("broker paused"));

        AtomicLong clock = new AtomicLong(1_000_000L);
        RocketMqDelayMessageAdapter adapter =
                new RocketMqDelayMessageAdapter(provider, producer, 5_000L, clock::get);

        assertTrue(adapter.available());
        assertThrows(IllegalStateException.class,
                () -> adapter.send("topic", "key", "{}", Instant.now().plusSeconds(5)));
        assertFalse(adapter.available(), "失败后应进入冷却，worker 重发任务据此快速跳过");

        clock.addAndGet(5_001L);
        assertTrue(adapter.available());
    }
}
