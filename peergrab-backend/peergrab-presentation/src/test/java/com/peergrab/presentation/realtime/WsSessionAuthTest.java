package com.peergrab.presentation.realtime;

import com.peergrab.domain.auth.ports.AuthPort;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WsSessionAuthTest {

    @Test
    void handshakeBindsTheValidatedTokenAndRejectsInvalidTokens() {
        AuthPort auth = mock(AuthPort.class);
        when(auth.resolve("valid-token")).thenReturn(Optional.of(42L));
        WsAuthHandshakeInterceptor interceptor = new WsAuthHandshakeInterceptor(auth);
        Map<String, Object> attributes = new HashMap<>();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("token", "valid-token");

        assertTrue(interceptor.beforeHandshake(new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(new MockHttpServletResponse()),
                mock(WebSocketHandler.class), attributes));
        assertEquals(42L, attributes.get(WsHandler.ATTR_USER_ID));
        assertEquals("valid-token", attributes.get(WsHandler.ATTR_TOKEN));

        request.setParameter("token", "invalid-token");
        assertFalse(interceptor.beforeHandshake(new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(new MockHttpServletResponse()),
                mock(WebSocketHandler.class), new HashMap<>()));
    }

    @Test
    void logoutOrExpiryClosesTheOldConnectionAndRemovesItFromBroadcasts() throws Exception {
        AuthPort auth = mock(AuthPort.class);
        when(auth.resolve("token")).thenReturn(Optional.of(42L), Optional.empty());
        WsSessionRegistry registry = new WsSessionRegistry();
        WsHandler handler = new WsHandler(registry, auth);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(Map.of(WsHandler.ATTR_USER_ID, 42L,
                WsHandler.ATTR_TOKEN, "token"));

        handler.afterConnectionEstablished(session);
        assertEquals(1, registry.onlineUsers());
        handler.revalidateSessions();

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        assertTrue(registry.of(42L).isEmpty());
        assertEquals(0, registry.onlineUsers());
    }

    @Test
    void changedIdentityAndUnverifiableSessionsAreClosed() throws Exception {
        AuthPort auth = mock(AuthPort.class);
        when(auth.resolve("token")).thenReturn(Optional.of(43L));
        WsSessionRegistry registry = new WsSessionRegistry();
        WsHandler handler = new WsHandler(registry, auth);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(Map.of(WsHandler.ATTR_USER_ID, 42L,
                WsHandler.ATTR_TOKEN, "token"));

        handler.afterConnectionEstablished(session);

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        assertEquals(0, registry.onlineUsers());
    }
}
