package com.peergrab.infrastructure.cache;

import com.peergrab.domain.grab.model.SlotOutcome;
import com.peergrab.domain.grab.ports.GrabSlotPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 抢单名额端口的 Redis Lua 实现——整个抢单链路的性能来源。
 *
 * 所有 key 都带 {errandId} 形式的 hash tag：保证 Redis Cluster 模式下
 * 同一任务的三个 key 落在同一个 slot，否则 Lua 脚本无法跨 slot 操作。
 * 单机模式下 hash tag 无害，但提前写好，将来上 Cluster 不用改代码。
 */
@Component
public class RedisGrabSlotAdapter implements GrabSlotPort {

    private static final long IDEM_TTL_SECONDS = 300;
    private static final long PENDING_TTL_SECONDS = 10;

    private final StringRedisTemplate redis;
    private final RedisScript<Long> grabScript;
    private final RedisScript<Long> rollbackScript;

    public RedisGrabSlotAdapter(StringRedisTemplate redis,
                               @Qualifier("grabScript") RedisScript<Long> grabScript,
                               @Qualifier("rollbackSlotScript") RedisScript<Long> rollbackScript) {
        this.redis = redis;
        this.grabScript = grabScript;
        this.rollbackScript = rollbackScript;
    }

    @Override
    public SlotOutcome tryAcquire(long errandId, long runnerId, String requestId) {
        Long code = redis.execute(grabScript,
                List.of(slotKey(errandId), grabbedKey(errandId), idemKey(errandId, requestId),
                        pendingKey(errandId)),
                String.valueOf(runnerId), String.valueOf(IDEM_TTL_SECONDS),
                String.valueOf(PENDING_TTL_SECONDS));
        if (code == null) {
            // 脚本没有返回裁决属于缓存异常，由数据库 CAS 保证不超卖并维持可抢性。
            return SlotOutcome.SLOT_MISSING;
        }
        return SlotOutcome.fromCode(code);
    }

    @Override
    public void rollback(long errandId, long runnerId, String requestId) {
        redis.execute(rollbackScript,
                List.of(slotKey(errandId), grabbedKey(errandId), idemKey(errandId, requestId),
                        pendingKey(errandId)),
                String.valueOf(runnerId));
    }

    @Override
    public void invalidate(long errandId) {
        // 回退可能已经跨过多轮，grabbed 集合保存的并非当前 grabber。
        // 丢弃旧快照后，开放任务的下一次请求走 DB CAS，不会被过期 Redis 状态堵住。
        redis.delete(List.of(slotKey(errandId), grabbedKey(errandId), pendingKey(errandId)));
    }

    @Override
    public boolean reservationPending(long errandId) {
        return Boolean.TRUE.equals(redis.hasKey(pendingKey(errandId)));
    }

    @Override
    public void initSlot(long errandId, int slotTotal, long ttlSeconds) {
        // May run after DB commit. A runner could have reserved the slot already, so
        // never overwrite an existing count or erase a live reservation marker.
        redis.opsForValue().setIfAbsent(slotKey(errandId), String.valueOf(slotTotal),
                Duration.ofSeconds(ttlSeconds));
    }

    @Override
    public long remainingSlot(long errandId) {
        String v = redis.opsForValue().get(slotKey(errandId));
        return v == null ? -1L : Long.parseLong(v);
    }

    private String slotKey(long errandId) {
        return "errand:slot:{" + errandId + "}";
    }

    private String grabbedKey(long errandId) {
        return "errand:grabbed:{" + errandId + "}";
    }

    private String idemKey(long errandId, String requestId) {
        return "errand:idem:{" + errandId + "}:" + requestId;
    }

    private String pendingKey(long errandId) {
        return "errand:pending:{" + errandId + "}";
    }
}
