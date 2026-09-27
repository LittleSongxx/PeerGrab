package com.peergrab.presentation.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peergrab.domain.auth.ports.AuthPort;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RealtimePushServiceTest {

    @Test
    void localEventsArePublishedAndRemoteEventsReachLocalSessions() throws Exception {
        WsSessionRegistry registry = new WsSessionRegistry();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(Map.of(WsHandler.ATTR_USER_ID, 42L,
                WsHandler.ATTR_TOKEN, "token"));
        AuthPort auth = mock(AuthPort.class);
        when(auth.resolve("token")).thenReturn(Optional.of(42L));
        CountDownLatch sent = new CountDownLatch(2);
        doAnswer(invocation -> { sent.countDown(); return null; })
                .when(session).sendMessage(any(TextMessage.class));
        registry.add(42, session);
        RealtimePushService service = new RealtimePushService(registry, redis, new ObjectMapper(),
                new WsHandler(registry, auth));
        try {
            service.notificationArrived(42, 123, "SETTLED", "任务已结算");
            verify(redis, timeout(2000)).convertAndSend(eq(RealtimePushService.CHANNEL), any(String.class));

            Message remote = new Message() {
                @Override public byte[] getBody() {
                    return "another-node|42|{\"type\":\"notification.new\"}"
                            .getBytes(StandardCharsets.UTF_8);
                }
                @Override public byte[] getChannel() {
                    return RealtimePushService.CHANNEL.getBytes(StandardCharsets.UTF_8);
                }
            };
            service.onMessage(remote, null);
            assertTrue(sent.await(2, TimeUnit.SECONDS));
            verify(session, times(2)).sendMessage(any(TextMessage.class));
        } finally {
            service.stop();
        }
    }

    @Test
    void structuredJsonPreservesControlCharactersAndExistingFieldTypes() throws Exception {
        WsSessionRegistry registry = new WsSessionRegistry();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(Map.of(WsHandler.ATTR_USER_ID, 42L,
                WsHandler.ATTR_TOKEN, "token"));
        AuthPort auth = mock(AuthPort.class);
        when(auth.resolve("token")).thenReturn(Optional.of(42L));
        AtomicReference<TextMessage> delivered = new AtomicReference<>();
        CountDownLatch sent = new CountDownLatch(1);
        doAnswer(invocation -> {
            delivered.set(invocation.getArgument(0));
            sent.countDown();
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        registry.add(42, session);
        RealtimePushService service = new RealtimePushService(registry, redis, new ObjectMapper(),
                new WsHandler(registry, auth));
        try {
            String content = "line 1\n\"quoted\"\\path\t控制";
            service.notificationArrived(42, 123, "SETTLED", content);
            assertTrue(sent.await(2, TimeUnit.SECONDS));
            JsonNode event = new ObjectMapper().readTree(delivered.get().getPayload());
            org.junit.jupiter.api.Assertions.assertEquals("notification.new", event.path("type").asText());
            org.junit.jupiter.api.Assertions.assertEquals("123", event.path("payload").path("errandId").asText());
            org.junit.jupiter.api.Assertions.assertTrue(event.path("payload").path("errandId").isTextual());
            org.junit.jupiter.api.Assertions.assertEquals(content, event.path("payload").path("content").asText());
        } finally {
            service.stop();
        }
    }

    @Test
    void revokedTokenCannotReceiveLocalOrRemoteEvents() throws Exception {
        WsSessionRegistry registry = new WsSessionRegistry();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        AuthPort auth = mock(AuthPort.class);
        when(auth.resolve("revoked")).thenReturn(Optional.empty());
        WebSocketSession local = mock(WebSocketSession.class);
        when(local.isOpen()).thenReturn(true);
        when(local.getAttributes()).thenReturn(Map.of(WsHandler.ATTR_USER_ID, 42L,
                WsHandler.ATTR_TOKEN, "revoked"));
        registry.add(42L, local);
        RealtimePushService service = new RealtimePushService(registry, redis, new ObjectMapper(),
                new WsHandler(registry, auth));
        try {
            service.notificationArrived(42L, 123L, "SETTLED", "secret");
            verify(redis, timeout(2000)).convertAndSend(eq(RealtimePushService.CHANNEL), any(String.class));
            verify(local).close(org.springframework.web.socket.CloseStatus.POLICY_VIOLATION);
            verify(local, never()).sendMessage(any(TextMessage.class));
            assertTrue(registry.of(42L).isEmpty());

            WebSocketSession remote = mock(WebSocketSession.class);
            when(remote.isOpen()).thenReturn(true);
            when(remote.getAttributes()).thenReturn(Map.of(WsHandler.ATTR_USER_ID, 42L,
                    WsHandler.ATTR_TOKEN, "revoked"));
            registry.add(42L, remote);
            Message event = new Message() {
                @Override public byte[] getBody() {
                    return "other-node|42|{\"type\":\"notification.new\"}"
                            .getBytes(StandardCharsets.UTF_8);
                }
                @Override public byte[] getChannel() {
                    return RealtimePushService.CHANNEL.getBytes(StandardCharsets.UTF_8);
                }
            };
            service.onMessage(event, null);
            verify(remote, timeout(2000)).close(org.springframework.web.socket.CloseStatus.POLICY_VIOLATION);
            verify(remote, never()).sendMessage(any(TextMessage.class));
        } finally {
            service.stop();
        }
    }
}
