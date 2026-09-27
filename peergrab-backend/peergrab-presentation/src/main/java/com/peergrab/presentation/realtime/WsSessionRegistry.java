package com.peergrab.presentation.realtime;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;
import java.util.function.BiConsumer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 在线连接注册表：userId -> 该用户的所有连接（多端登录）。
 *
 * 用 CopyOnWriteArraySet：推送是遍历读，连接增删是低频写，
 * 读多写少场景下它比同步集合更合适。
 */
@Component
public class WsSessionRegistry {

    private final ConcurrentHashMap<Long, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    public void add(long userId, WebSocketSession session) {
        sessions.compute(userId, (id, current) -> {
            Set<WebSocketSession> active = current == null ? new CopyOnWriteArraySet<>() : current;
            active.add(session);
            return active;
        });
    }

    public void remove(long userId, WebSocketSession session) {
        sessions.computeIfPresent(userId, (id, active) -> {
            active.remove(session);
            return active.isEmpty() ? null : active;
        });
    }

    public Set<WebSocketSession> of(long userId) {
        return sessions.getOrDefault(userId, Set.of());
    }

    /** Weakly consistent iteration is safe while other threads add or remove connections. */
    public void forEach(BiConsumer<Long, WebSocketSession> action) {
        sessions.forEach((userId, active) -> active.forEach(session -> action.accept(userId, session)));
    }

    public int onlineUsers() {
        return sessions.size();
    }
}
