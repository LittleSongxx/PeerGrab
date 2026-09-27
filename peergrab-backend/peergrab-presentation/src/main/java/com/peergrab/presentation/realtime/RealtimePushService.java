package com.peergrab.presentation.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peergrab.domain.notify.ports.RealtimeNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;

/**
 * WebSocket 推送实现（peergrab.ws.enabled=true 时装配）。
 *
 * 推送协议：JSON {type, payload}，type 三种：
 *   errand.status    任务状态变更
 *   notification.new 站内消息到达
 *   credit.changed   信用分变更
 *
 * 推送失败只记日志不重试：实时推送是体验优化，事实以 DB 为准，
 * 前端有轮询兜底。为推送做重试队列是本末倒置。
 */
@Component
@ConditionalOnProperty(name = "peergrab.ws.enabled", havingValue = "true", matchIfMissing = true)
public class RealtimePushService implements RealtimeNotifier, MessageListener {

    private static final Logger log = LoggerFactory.getLogger(RealtimePushService.class);
    public static final String CHANNEL = "peergrab:realtime:events";

    private final WsSessionRegistry registry;
    private final WsHandler wsHandler;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final String instanceId = UUID.randomUUID().toString();
    // A slow browser must not hold a database transaction or exhaust request threads.
    private final ThreadPoolExecutor dispatcher = new ThreadPoolExecutor(
            2, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1024),
            new ThreadPoolExecutor.AbortPolicy());

    public RealtimePushService(WsSessionRegistry registry, StringRedisTemplate redis,
                               ObjectMapper objectMapper, WsHandler wsHandler) {
        this.registry = registry;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.wsHandler = wsHandler;
    }

    @Override
    public void errandStatusChanged(long errandId, long publisherId, Long grabberId,
                                    String status, int round) {
        String msg = encode("errand.status", Map.of("errandId", Long.toString(errandId),
                "status", Objects.toString(status, ""), "round", round));
        if (msg == null) return;
        push(publisherId, msg);
        if (grabberId != null && grabberId != publisherId) {
            push(grabberId, msg);
        }
    }

    @Override
    public void notificationArrived(long userId, long errandId, String type, String content) {
        String msg = encode("notification.new", Map.of("errandId", Long.toString(errandId),
                "noticeType", Objects.toString(type, ""), "content", Objects.toString(content, "")));
        if (msg == null) return;
        push(userId, msg);
    }

    @Override
    public void creditChanged(long userId, int newScore, int delta, String reason) {
        String msg = encode("credit.changed", Map.of("score", newScore,
                "delta", delta, "reason", Objects.toString(reason, "")));
        if (msg == null) return;
        push(userId, msg);
    }

    private String encode(String type, Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(Map.of("type", type, "payload", payload));
        } catch (JsonProcessingException e) {
            log.warn("实时事件序列化失败 type={}", type, e);
            return null;
        }
    }

    private void push(long userId, String json) {
        try {
            dispatcher.execute(() -> {
                pushLocal(userId, json);
                try {
                    redis.convertAndSend(CHANNEL, instanceId + "|" + userId + "|" + json);
                } catch (RuntimeException e) {
                    // Realtime events are best-effort; clients periodically reconcile with MySQL.
                    log.warn("跨节点实时推送失败 userId={}", userId, e);
                }
            });
        } catch (RuntimeException e) {
            log.warn("实时推送队列已满，交由客户端轮询恢复 userId={}", userId);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String envelope = new String(message.getBody(), StandardCharsets.UTF_8);
        int first = envelope.indexOf('|');
        int second = envelope.indexOf('|', first + 1);
        if (first < 0 || second < 0 || envelope.substring(0, first).equals(instanceId)) return;
        try {
            long userId = Long.parseLong(envelope.substring(first + 1, second));
            String json = envelope.substring(second + 1);
            dispatcher.execute(() -> pushLocal(userId, json));
        } catch (RuntimeException e) {
            log.warn("跨节点实时事件无效或队列已满", e);
        }
    }

    private void pushLocal(long userId, String json) {
        for (WebSocketSession session : registry.of(userId)) {
            try {
                // Revalidate under the same lock as send so revocation closes the connection
                // before an event is delivered, including events received from other API nodes.
                synchronized (session) {
                    if (!wsHandler.validateSession(userId, session)) continue;
                    session.sendMessage(new TextMessage(json));
                }
            } catch (IOException | RuntimeException e) {
                log.debug("推送失败 userId={}（连接可能已断）", userId);
            }
        }
    }

    @PreDestroy
    public void stop() {
        dispatcher.shutdownNow();
    }

}
