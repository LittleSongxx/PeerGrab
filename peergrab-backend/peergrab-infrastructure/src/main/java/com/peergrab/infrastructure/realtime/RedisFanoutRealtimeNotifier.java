package com.peergrab.infrastructure.realtime;

import com.peergrab.domain.notify.ports.RealtimeNotifier;
import com.peergrab.shared.RealtimeEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 无 WebSocket 连接的进程（当前是 worker）使用的实时通知实现。
 *
 * <p>worker 写出的递补/回退/资金事件通知需要让挂在 API 进程上的 WebSocket 用户收到：
 * 这里把事件发布到与 API 相同的 Redis Pub/Sub 频道，由 API 副本分发给本机连接。
 * 发布失败只记日志——持久通知已落库、前端也会轮询兜底，实时推送是体验优化。</p>
 */
@Component
@ConditionalOnProperty(name = "peergrab.ws.enabled", havingValue = "false")
public class RedisFanoutRealtimeNotifier implements RealtimeNotifier {

    private static final Logger log = LoggerFactory.getLogger(RedisFanoutRealtimeNotifier.class);

    private final StringRedisTemplate redis;
    /** 任何非空且与 API 实例不同的值都可；worker 重启后换新值避免与旧回环混淆。 */
    private final String instanceId = "worker-" + UUID.randomUUID();

    public RedisFanoutRealtimeNotifier(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void errandStatusChanged(long errandId, long publisherId, Long grabberId,
                                    String status, int round) {
        publish(publisherId, RealtimeEvents.errandStatus(errandId, status, round));
        if (grabberId != null && grabberId != publisherId) {
            publish(grabberId, RealtimeEvents.errandStatus(errandId, status, round));
        }
    }

    @Override
    public void notificationArrived(long userId, long errandId, String type, String content) {
        publish(userId, RealtimeEvents.notification(errandId, type, content));
    }

    @Override
    public void creditChanged(long userId, int newScore, int delta, String reason) {
        publish(userId, RealtimeEvents.creditChanged(newScore, delta, reason));
    }

    private void publish(long userId, String payloadJson) {
        try {
            redis.convertAndSend(RealtimeEvents.CHANNEL,
                    RealtimeEvents.envelope(instanceId, userId, payloadJson));
        } catch (RuntimeException e) {
            log.warn("跨进程实时推送失败 userId={}，客户端将轮询同步", userId, e);
        }
    }
}
