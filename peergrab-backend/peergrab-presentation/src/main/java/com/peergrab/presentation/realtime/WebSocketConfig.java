package com.peergrab.presentation.realtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 装配：/ws 端点 + 握手鉴权。
 * The WebSocket origin allowlist matches the REST API's configured frontend origins.
 */
@Configuration
@EnableWebSocket
@ConditionalOnProperty(name = "peergrab.ws.enabled", havingValue = "true", matchIfMissing = true)
public class WebSocketConfig implements WebSocketConfigurer {

    private final WsHandler wsHandler;
    private final WsAuthHandshakeInterceptor handshakeInterceptor;
    private final String[] allowedOrigins;

    public WebSocketConfig(WsHandler wsHandler, WsAuthHandshakeInterceptor handshakeInterceptor,
                           @Value("${peergrab.web.cors-origins:http://localhost:5173}") String corsOrigins) {
        this.wsHandler = wsHandler;
        this.handshakeInterceptor = handshakeInterceptor;
        this.allowedOrigins = java.util.Arrays.stream(corsOrigins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(wsHandler, "/ws")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins(allowedOrigins);
    }
}
