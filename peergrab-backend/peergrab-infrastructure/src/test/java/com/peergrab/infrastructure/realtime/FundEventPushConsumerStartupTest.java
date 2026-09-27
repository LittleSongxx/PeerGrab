package com.peergrab.infrastructure.realtime;

import com.peergrab.domain.notify.ports.RealtimeNotifier;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.consumer.PushConsumerBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FundEventPushConsumerStartupTest {

    @Test
    void brokerUnavailableAtStartupDoesNotAbortApplicationAndLaterConnects() throws Exception {
        var provider = mock(ClientServiceProvider.class);
        var config = mock(ClientConfiguration.class);
        var notifier = mock(RealtimeNotifier.class);
        var builder = mock(PushConsumerBuilder.class);
        var consumer = mock(PushConsumer.class);
        when(provider.newPushConsumerBuilder()).thenThrow(new IllegalStateException("broker unavailable"))
                .thenReturn(builder);
        when(builder.setClientConfiguration(config)).thenReturn(builder);
        when(builder.setConsumerGroup(anyString())).thenReturn(builder);
        when(builder.setSubscriptionExpressions(anyMap())).thenReturn(builder);
        when(builder.setMessageListener(any())).thenReturn(builder);
        when(builder.build()).thenReturn(consumer);
        var push = new FundEventPushConsumer(provider, config, notifier,
                "errand-fund-event", "peergrab-fund-event-push");

        assertDoesNotThrow(push::ensureStarted);
        push.ensureStarted();
        push.ensureStarted();

        verify(provider, times(2)).newPushConsumerBuilder();
        verify(builder).build();
        push.stop();
        verify(consumer).close();
    }
}
