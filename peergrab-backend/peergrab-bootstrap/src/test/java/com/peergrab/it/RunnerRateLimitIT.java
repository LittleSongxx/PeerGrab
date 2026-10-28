package com.peergrab.it;

import com.peergrab.domain.grab.ports.RunnerRateLimiterPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 跑腿双桶限流的真实 Redis 集成测试。
 *
 * <p>刻意收紧桶参数：跨任务桶 2、单任务桶 1、查询桶 2，验证：
 * <ul>
 *   <li>跨任务预算被共享（换任务也消耗同一个跑腿桶）；</li>
 *   <li>单任务桶独立限制同一跑腿对同一任务的重复尝试；</li>
 *   <li>查询使用独立预算，不会被抢单桶串扰；</li>
 *   <li>限流返回可读的 Retry-After。</li>
 * </ul>
 * 需要可丢弃 Redis；仅 -Dpeergrab.it=true 时执行。</p>
 */
@SpringBootTest(properties = {
        "peergrab.grab.runner-limit.rate=0.1",
        "peergrab.grab.runner-limit.burst=2",
        "peergrab.grab.runner-limit.errand-rate=0.1",
        "peergrab.grab.runner-limit.errand-burst=1",
        "peergrab.grab.runner-limit.query-rate=0.1",
        "peergrab.grab.runner-limit.query-burst=2",
        "peergrab.grab.runner-limit.recovery-query-per-second=1"
})
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "peergrab.it", matches = "true")
class RunnerRateLimitIT {

    @Autowired RunnerRateLimiterPort limiter;
    @Autowired StringRedisTemplate redis;

    @BeforeAll
    static void requireMiddleware() {
        MiddlewareAvailable.require();
    }

    @BeforeEach
    void cleanKeys() {
        Set<String> keys = redis.keys("peergrab:rl:v1:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @AfterEach
    void cleanKeysAfter() {
        cleanKeys();
    }

    @Test
    @DisplayName("跨任务桶：同一跑腿的预算跨任务共享，耗尽后 429 并带重试时间")
    void cross_task_bucket_is_shared() {
        long runner = 61001L;
        assertAllowed(limiter.checkGrab(runner, 71001L));
        assertAllowed(limiter.checkGrab(runner, 71002L));

        RunnerRateLimiterPort.Decision third = limiter.checkGrab(runner, 71003L);
        assertEquals(RunnerRateLimiterPort.Status.LIMITED, third.status());
        assertTrue(third.retryAfterMillis() > 0, "限流结果必须携带 Retry-After");
    }

    @Test
    @DisplayName("单任务桶：同一跑腿对同一任务的重复尝试被独立限制")
    void per_errand_bucket_is_independent() {
        long runner = 61002L;
        long errand = 71004L;
        assertAllowed(limiter.checkGrab(runner, errand));

        RunnerRateLimiterPort.Decision second = limiter.checkGrab(runner, errand);
        assertEquals(RunnerRateLimiterPort.Status.LIMITED, second.status());
    }

    @Test
    @DisplayName("查询桶独立：抢单桶耗尽不影响查询，查询自身有界")
    void query_budget_is_independent() {
        long runner = 61003L;
        assertAllowed(limiter.checkGrab(runner, 71005L));
        assertAllowed(limiter.checkGrab(runner, 71006L));

        assertAllowed(limiter.checkQuery(runner));
        assertAllowed(limiter.checkQuery(runner));
        assertEquals(RunnerRateLimiterPort.Status.LIMITED, limiter.checkQuery(runner).status());
    }

    private static void assertAllowed(RunnerRateLimiterPort.Decision decision) {
        assertEquals(RunnerRateLimiterPort.Status.ALLOWED, decision.status());
    }
}
