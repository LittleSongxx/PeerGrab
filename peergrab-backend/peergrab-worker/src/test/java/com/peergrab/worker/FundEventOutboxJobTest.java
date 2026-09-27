package com.peergrab.worker;

import com.peergrab.infrastructure.persistence.JdbcFundEventOutboxRepository;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.message.MessageBuilder;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.peergrab.shared.MessagePayloadCodec;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FundEventOutboxJobTest {

    private final JdbcFundEventOutboxRepository outbox = mock(JdbcFundEventOutboxRepository.class);
    private final ClientServiceProvider provider = mock(ClientServiceProvider.class);
    private final Producer producer = mock(Producer.class);
    private final MessageBuilder builder = mock(MessageBuilder.class);
    private final Message message = mock(Message.class);
    private final FundEventOutboxJob job = new FundEventOutboxJob(outbox, provider, producer,
            "errand-fund-event");

    @BeforeEach
    void setUp() {
        when(outbox.claimPending(100)).thenReturn(List.of(new JdbcFundEventOutboxRepository.ClaimedEvent(
                new JdbcFundEventOutboxRepository.PendingEvent(
                        "settle:41", "SETTLED", 41, 1001, 2001, 950, 50, 0), "claim-1")));
        when(provider.newMessageBuilder()).thenReturn(builder);
        when(builder.setTopic(anyString())).thenReturn(builder);
        when(builder.setKeys(any(String[].class))).thenReturn(builder);
        when(builder.setTag(anyString())).thenReturn(builder);
        when(builder.setBody(any(byte[].class))).thenReturn(builder);
        when(builder.build()).thenReturn(message);
        when(outbox.markClaimedSent("settle:41", "claim-1")).thenReturn(true);
    }

    @Test
    void marksSentOnlyAfterBrokerAcceptsEvent() throws Exception {
        job.dispatch();

        var order = inOrder(producer, outbox);
        order.verify(producer).send(message);
        order.verify(outbox).markClaimedSent("settle:41", "claim-1");
        verify(outbox, never()).markClaimedRetry(anyString(), anyString());
        verify(builder).setKeys("settle:41");
        verify(builder).setTag("SETTLED");
        verify(builder).setBody(MessagePayloadCodec.fundEvent("settle:41", "SETTLED",
                41, 1001, 2001, 950, 50).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void brokerFailureKeepsEventRetryable() throws Exception {
        when(producer.send(message)).thenThrow(new IllegalStateException("broker unavailable"));

        job.dispatch();

        verify(outbox).markClaimedRetry("settle:41", "claim-1");
        verify(outbox, never()).markClaimedSent(anyString(), anyString());
    }
}
