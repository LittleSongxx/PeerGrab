package com.peergrab.it;

import com.peergrab.infrastructure.cache.RedisCandidateQueueAdapter;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"peergrab.mq.enabled=false"})
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "peergrab.it", matches = "true")
class CandidateLeaseIT {

    @Autowired StringRedisTemplate redis;

    @BeforeAll
    static void requireMiddleware() {
        Assumptions.assumeTrue(MiddlewareAvailable.check(), "中间件未启动，跳过");
    }

    @Test
    void expired_claim_recovers_and_stale_worker_cannot_ack_new_claim() throws Exception {
        long errandId = Math.abs(System.nanoTime());
        var queue = new RedisCandidateQueueAdapter(redis, 60, 1);
        try {
            queue.offer(errandId, 2002L, 12.5);
            var first = queue.pollBest(errandId).orElseThrow();
            assertEquals(12.5, first.score());
            assertTrue(queue.pollBest(errandId).isEmpty(), "有效租约不能被第二个 worker 重复领取");
            queue.offer(errandId, 2002L, 99.0);
            assertEquals(1, queue.size(errandId), "租约中的候选不能重复入队");

            Thread.sleep(1200);
            var second = queue.pollBest(errandId).orElseThrow();
            assertEquals(first.runnerId(), second.runnerId());
            assertEquals(first.score(), second.score(), "恢复后保留原排队分数");
            assertNotEquals(first.claimId(), second.claimId());

            queue.acknowledge(errandId, first);
            assertEquals(1, queue.size(errandId), "旧 worker 不能删除新租约");
            queue.release(errandId, second);
            var third = queue.pollBest(errandId).orElseThrow();
            assertEquals(12.5, third.score());
            queue.acknowledge(errandId, third);
            assertEquals(0, queue.size(errandId));
        } finally {
            queue.clear(errandId);
        }
    }
}
