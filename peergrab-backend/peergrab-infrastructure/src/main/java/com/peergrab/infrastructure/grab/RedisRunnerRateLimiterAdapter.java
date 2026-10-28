package com.peergrab.infrastructure.grab;

import com.peergrab.domain.grab.ports.RunnerRateLimiterPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 跨实例的跑腿双桶限流（Redis + Lua 令牌桶）。
 *
 * <p>为什么不用本地计数器：API 多副本时每实例各放行一份，等于额度按副本数放大；
 * 这里所有桶都放在 Redis，脚本先统一检查再统一扣减，不存在“一个桶扣了、另一个桶拒绝”的半扣减。
 * 时钟使用 Redis TIME，并保留高水位，避免 NTP 回拨时重复补充令牌。</p>
 *
 * <p>Redis 异常时返回 UNAVAILABLE 而不是放行：抢单入口会返回 503，
 * 客户端保留 requestId 重试；这比“限流失效”更安全。</p>
 */
@Component
public class RedisRunnerRateLimiterAdapter implements RunnerRateLimiterPort {

    private static final Logger log = LoggerFactory.getLogger(RedisRunnerRateLimiterAdapter.class);
    private static final String KEY_PREFIX = "peergrab:rl:v1:{runner:";
    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>();

    static {
        SCRIPT.setLocation(new ClassPathResource("lua/runner-rate-limit.lua"));
        SCRIPT.setResultType(List.class);
    }

    private final StringRedisTemplate redis;
    private final double runnerRate;
    private final double errandRate;
    private final double queryRate;
    private final int runnerBurst;
    private final int errandBurst;
    private final int queryBurst;
    private final int recoveryPerSecond;

    private long recoverySecond;
    private int recoveryCount;

    public RedisRunnerRateLimiterAdapter(
            StringRedisTemplate redis,
            @Value("${peergrab.grab.runner-limit.rate:2}") double runnerRate,
            @Value("${peergrab.grab.runner-limit.burst:4}") int runnerBurst,
            @Value("${peergrab.grab.runner-limit.errand-rate:0.5}") double errandRate,
            @Value("${peergrab.grab.runner-limit.errand-burst:2}") int errandBurst,
            @Value("${peergrab.grab.runner-limit.query-rate:5}") double queryRate,
            @Value("${peergrab.grab.runner-limit.query-burst:10}") int queryBurst,
            @Value("${peergrab.grab.runner-limit.recovery-query-per-second:10}") int recoveryPerSecond) {
        if (runnerRate <= 0 || errandRate <= 0 || queryRate <= 0
                || runnerBurst < 1 || errandBurst < 1 || queryBurst < 1 || recoveryPerSecond < 1
                || !Double.isFinite(runnerRate) || !Double.isFinite(errandRate) || !Double.isFinite(queryRate)) {
            throw new IllegalArgumentException("跑腿限流速率与容量必须为有效正数");
        }
        this.redis = redis;
        this.runnerRate = runnerRate;
        this.runnerBurst = runnerBurst;
        this.errandRate = errandRate;
        this.errandBurst = errandBurst;
        this.queryRate = queryRate;
        this.queryBurst = queryBurst;
        this.recoveryPerSecond = recoveryPerSecond;
    }

    @Override
    public Decision checkGrab(long runnerId, long errandId) {
        if (runnerId <= 0 || errandId <= 0) {
            return Decision.unavailable();
        }
        String prefix = KEY_PREFIX + runnerId + "}:";
        return execute(List.of(prefix + "all", prefix + "errand:" + errandId),
                runnerRate, runnerBurst, errandRate, errandBurst);
    }

    @Override
    public Decision checkQuery(long runnerId) {
        if (runnerId <= 0) {
            return Decision.unavailable();
        }
        Decision decision = execute(List.of(KEY_PREFIX + runnerId + "}:query"),
                queryRate, queryBurst);
        // Redis 整体不可用时，查询仍允许极小的本机恢复预算，避免 202 请求永远拿不到结果。
        return decision.status() == Status.UNAVAILABLE ? recoveryBudget() : decision;
    }

    private Decision execute(List<String> keys, Object... values) {
        try {
            Object[] args = new Object[values.length];
            for (int i = 0; i < values.length; i++) {
                args[i] = String.valueOf(values[i]);
            }
            List<?> result = redis.execute(SCRIPT, keys, args);
            if (result == null || result.size() != 2) {
                return Decision.unavailable();
            }
            return ((Number) result.get(0)).longValue() == 1
                    ? Decision.allowed()
                    : Decision.limited(((Number) result.get(1)).longValue());
        } catch (RuntimeException e) {
            log.warn("跑腿共享限流不可用：{}", e.getClass().getSimpleName());
            return Decision.unavailable();
        }
    }

    private synchronized Decision recoveryBudget() {
        long second = System.nanoTime() / 1_000_000_000L;
        if (second != recoverySecond) {
            recoverySecond = second;
            recoveryCount = 0;
        }
        if (recoveryCount >= recoveryPerSecond) {
            return Decision.unavailable();
        }
        recoveryCount++;
        return Decision.allowed();
    }
}
