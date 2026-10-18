package com.peergrab.presentation.realtime;

import com.peergrab.domain.auth.ports.AuthPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket 连接处理器：只管连接生命周期，消息内容全由服务端主动推。
 *
 * 不做"客户端订阅任务"协议：本项目量级下按 userId 广播足够，
 * 订阅协议会带来"订阅状态与业务状态漂移"这类新问题。
 *
 * 客户端可以发 ping，服务端回 pong（心跳保活，防中间设备断连）。
 */
@Component
@ConditionalOnProperty(name = "peergrab.ws.enabled", havingValue = "true", matchIfMissing = true)
public class WsHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(WsHandler.class);
    public static final String ATTR_USER_ID = "wsUserId";
    public static final String ATTR_TOKEN = "wsToken";

    private final WsSessionRegistry registry;
    private final AuthPort authPort;
    private final int maxMessagesPerSecond;
    /** sessionId -> 当前秒 + 计数；用于单连接消息频率限制。 */
    private final ConcurrentHashMap<String, MessageWindow> messageWindows = new ConcurrentHashMap<>();

    public WsHandler(WsSessionRegistry registry, AuthPort authPort) {
        this(registry, authPort, 5);
    }

    @Autowired
    public WsHandler(WsSessionRegistry registry, AuthPort authPort,
                     @Value("${peergrab.ws.max-messages-per-second:5}") int maxMessagesPerSecond) {
        this.registry = registry;
        this.authPort = authPort;
        this.maxMessagesPerSecond = Math.max(1, maxMessagesPerSecond);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = (Long) session.getAttributes().get(ATTR_USER_ID);
        if (userId == null) {
            closeAndRemove(session, userId);
            return;
        }
        if (!validateSession(userId, session)) return;
        if (!registry.add(userId, session)) {
            // 单用户连接数超限：拒绝新连接，避免恶意/异常客户端耗尽服务端资源。
            closeAndRemove(session, userId);
        }
    }

    /** Logout and expiry must invalidate connections opened before the change. */
    @Scheduled(fixedDelayString = "${peergrab.ws.auth-recheck-ms:60000}",
            scheduler = "realtimeTaskScheduler")
    public void revalidateSessions() {
        registry.forEach((userId, session) -> {
            synchronized (session) {
                validateSession(userId, session);
            }
        });
    }

    /** Called under the session lock before every delivery as well as by the idle scan. */
    boolean validateSession(long userId, WebSocketSession session) {
        if (session.isOpen() && isStillAuthorized(session, userId)) return true;
        closeAndRemove(session, userId);
        return false;
    }

    private boolean isStillAuthorized(WebSocketSession session, long userId) {
        Object token = session.getAttributes().get(ATTR_TOKEN);
        if (!(token instanceof String value) || value.isBlank()) return false;
        try {
            return authPort.resolve(value).filter(id -> id == userId).isPresent();
        } catch (RuntimeException e) {
            // No delivery is safe while the session's current identity cannot be verified.
            log.warn("WebSocket 会话复核失败，关闭连接 userId={}", userId, e);
            return false;
        }
    }

    private void closeAndRemove(WebSocketSession session, Long userId) {
        if (userId != null) registry.remove(userId, session);
        if (!session.isOpen()) return;
        try {
            synchronized (session) {
                session.close(CloseStatus.POLICY_VIOLATION);
            }
        } catch (Exception e) {
            log.debug("WebSocket 失效连接关闭失败 userId={}", userId, e);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if (!allowClientMessage(session)) {
            synchronized (session) {
                session.close(CloseStatus.POLICY_VIOLATION);
            }
            return;
        }
        if ("ping".equals(message.getPayload())) {
            synchronized (session) {
                session.sendMessage(new TextMessage("pong"));
            }
        }
        // 其他客户端消息一律忽略：本协议只有服务端 -> 客户端方向有业务语义
    }

    /** 单连接消息频率限制：超过阈值的连接按策略违规关闭，防止刷心跳/刷消息。 */
    private boolean allowClientMessage(WebSocketSession session) {
        long second = System.currentTimeMillis() / 1000L;
        MessageWindow window = messageWindows.compute(session.getId(), (id, existing) ->
                existing == null || existing.second != second ? new MessageWindow(second) : existing);
        if (window.count.incrementAndGet() > maxMessagesPerSecond) {
            messageWindows.remove(session.getId(), window);
            return false;
        }
        return true;
    }

    private static final class MessageWindow {
        private final long second;
        private final AtomicInteger count = new AtomicInteger();

        private MessageWindow(long second) {
            this.second = second;
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        messageWindows.remove(session.getId());
        Long userId = (Long) session.getAttributes().get(ATTR_USER_ID);
        if (userId != null) {
            registry.remove(userId, session);
        }
    }
}
