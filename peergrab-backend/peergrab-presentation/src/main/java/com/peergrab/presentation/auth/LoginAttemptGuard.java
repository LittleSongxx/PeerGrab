package com.peergrab.presentation.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 登录失败限速。
 *
 * <p>演示环境固定四个身份且口令来自配置，若没有失败次数限制，公开站点上的暴力尝试
 * 会直接威胁仲裁员这类特权身份。这里实现"IP + userId 维度"的滑动窗口限速：
 * 窗口内失败达到阈值后锁定一段时间；成功登录清除计数。</p>
 *
 * <p>状态在进程内存：当前单 API 实例部署足够；多实例部署时降级为"每实例限速"，
 * 需要分布式限速时再替换为 Redis 实现。</p>
 */
@Component
public class LoginAttemptGuard {

    private final boolean enabled;
    private final int maxAttempts;
    private final long windowMillis;
    private final long lockMillis;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Attempts> attempts = new ConcurrentHashMap<>();
    private final AtomicLong lastPruneMillis = new AtomicLong();

    @Autowired
    public LoginAttemptGuard(@Value("${peergrab.auth.login.enabled:true}") boolean enabled,
                             @Value("${peergrab.auth.login.max-attempts:5}") int maxAttempts,
                             @Value("${peergrab.auth.login.window-seconds:60}") int windowSeconds,
                             @Value("${peergrab.auth.login.lock-seconds:300}") int lockSeconds) {
        this(enabled, maxAttempts, windowSeconds, lockSeconds, System::currentTimeMillis);
    }

    /** 测试用时钟注入构造。 */
    LoginAttemptGuard(boolean enabled, int maxAttempts, int windowSeconds, int lockSeconds,
                      LongSupplier clock) {
        this.enabled = enabled;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.windowMillis = Math.max(1, windowSeconds) * 1000L;
        this.lockMillis = Math.max(1, lockSeconds) * 1000L;
        this.clock = clock;
    }

    /** 是否允许本次登录尝试（只读，不增加失败计数）。 */
    public boolean allow(String key) {
        if (!enabled) return true;
        long now = clock.getAsLong();
        pruneStale(now);
        Attempts current = attempts.compute(key, (ignored, existing) -> {
            if (existing == null) return new Attempts(now);
            if (existing.lockedUntil > now) return existing;
            if (now - existing.windowStart >= windowMillis) return new Attempts(now);
            return existing;
        });
        return current.lockedUntil <= now;
    }

    public void recordFailure(String key) {
        if (!enabled) return;
        long now = clock.getAsLong();
        attempts.compute(key, (ignored, existing) -> {
            Attempts bucket = existing == null || now - existing.windowStart >= windowMillis
                    ? new Attempts(now) : existing;
            bucket.failures++;
            if (bucket.failures >= maxAttempts) {
                bucket.lockedUntil = now + lockMillis;
            }
            return bucket;
        });
    }

    public void recordSuccess(String key) {
        if (!enabled) return;
        attempts.remove(key);
    }

    /** 定期清理"窗口已过且未锁定"的 key，避免大量一次性来源把内存撑大。 */
    private void pruneStale(long now) {
        long last = lastPruneMillis.get();
        if (now - last < 60_000L) return;
        if (!lastPruneMillis.compareAndSet(last, now)) return;
        attempts.entrySet().removeIf(entry -> {
            Attempts attemptsForIp = entry.getValue();
            return attemptsForIp.lockedUntil <= now && now - attemptsForIp.windowStart >= windowMillis;
        });
    }

    /** 测试/指标用：当前跟踪的 key 数量。 */
    int trackedKeys() {
        return attempts.size();
    }

    /** 优先取代理后的首个 X-Forwarded-For，其次 remoteAddr；仅用于限速维度。 */
    public static String clientIp(jakarta.servlet.http.HttpServletRequest request) {
        if (request == null) return "unknown";
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }

    private static final class Attempts {
        private final long windowStart;
        private int failures;
        private long lockedUntil;

        private Attempts(long windowStart) {
            this.windowStart = windowStart;
        }
    }
}
