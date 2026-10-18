package com.peergrab.shared;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 实时推送事件协议。
 *
 * <p>API 进程的 WebSocket 与 worker 进程的 Redis fanout 必须写出完全一致的
 * {@code {type,payload}} JSON 与信封格式（{@code instanceId|userId|payload}），
 * 所以协议集中放在 shared 层，避免两个进程各写一份而漂移。</p>
 */
public final class RealtimeEvents {

    /** Redis Pub/Sub 频道：所有 API 副本订阅，按 userId 分发到本机连接。 */
    public static final String CHANNEL = "peergrab:realtime:events";

    private static final ObjectMapper JSON = new ObjectMapper();

    private RealtimeEvents() {
    }

    public static String errandStatus(long errandId, String status, int round) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("errandId", Long.toString(errandId));
        payload.put("status", status == null ? "" : status);
        payload.put("round", round);
        return event("errand.status", payload);
    }

    public static String notification(long errandId, String noticeType, String content) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("errandId", Long.toString(errandId));
        payload.put("noticeType", noticeType == null ? "" : noticeType);
        payload.put("content", content == null ? "" : content);
        return event("notification.new", payload);
    }

    public static String creditChanged(int score, int delta, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("score", score);
        payload.put("delta", delta);
        payload.put("reason", reason == null ? "" : reason);
        return event("credit.changed", payload);
    }

    /** 跨副本信封：{@code instanceId|userId|json}；接收端按 instanceId 跳过自己发布的回环。 */
    public static String envelope(String instanceId, long userId, String payloadJson) {
        return instanceId + "|" + userId + "|" + payloadJson;
    }

    private static String event(String type, Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", type);
        envelope.put("payload", payload);
        try {
            return JSON.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot encode realtime event", e);
        }
    }
}
