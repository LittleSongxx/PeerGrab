package com.peergrab.it;

import com.peergrab.application.usecase.GrabErrandUseCase;
import com.peergrab.application.usecase.PublishErrandUseCase;
import com.peergrab.application.usecase.query.GetErrandDetailUseCase;
import com.peergrab.domain.errand.model.ErrandType;
import com.peergrab.domain.errand.ports.ErrandCachePort;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A slow reader can repopulate an old snapshot after the writer commits and
 * invalidates. This test injects that old result deterministically; no unsafe
 * BEFORE_COMMIT option is needed in production to demonstrate the window.
 */
@SpringBootTest(properties = "peergrab.mq.enabled=false")
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "peergrab.it", matches = "true")
class CacheStaleReaderWindowIT {

    @Autowired PublishErrandUseCase publishUseCase;
    @Autowired GrabErrandUseCase grabUseCase;
    @Autowired GetErrandDetailUseCase detailUseCase;
    @Autowired ErrandCachePort cache;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    static void requireMiddleware() {
        Assumptions.assumeTrue(MiddlewareAvailable.check(), "中间件未启动，跳过");
    }

    @BeforeEach
    void reset() {
        jdbc.update("UPDATE wallet_account SET available = 100000, frozen = 0 WHERE owner_id = 1001 AND owner_type = 'USER'");
        jdbc.update("UPDATE wallet_account SET available = 5000, frozen = 0 WHERE owner_id = 2001 AND owner_type = 'USER'");
        detailUseCase.resetStats();
    }

    @Test
    @DisplayName("慢读在提交后失效结束才回填，详情可能暂时陈旧")
    void slow_reader_can_repopulate_after_commit_eviction() {
        var pub = publishUseCase.publish(new PublishErrandUseCase.Command(
                1L, 1001L, ErrandType.DELIVERY,
                "对照_先删后更_" + UUID.randomUUID().toString().substring(0, 6), 1000L, 1));
        long id = pub.errandId();

        // 1. 读一次，缓存回填 PUBLISHED，并记下旧值
        String staleJson = detailUseCase.detailJson(id).orElseThrow();
        assertTrue(staleJson.contains("\"status\":\"PUBLISHED\""));

        // 2. A read began before the commit and holds an old snapshot.
        cache.evict(id);

        // 3. Commit PUBLISHED -> LOCKED. The normal afterCommit delete runs.
        grabUseCase.grab(new GrabErrandUseCase.Command(id, 2001L, UUID.randomUUID().toString(), 60));

        // 4. The earlier read finally writes its stale result. A fixed second
        // delete cannot prove strict consistency if this happens after its delay.
        cache.put(id, staleJson);

        // 5. Read the stale result until TTL or a later reconciliation removes it.
        String after = detailUseCase.detailJson(id).orElseThrow();
        assertTrue(after.contains("\"status\":\"PUBLISHED\""),
                "预期读到慢读者回填的旧值，实际: " + after);
        assertFalse(after.contains("\"status\":\"LOCKED\""),
                "若读到新值，说明模拟时序不成立");

        // 6. 佐证 DB 里确实是新值：不一致只存在于缓存层
        String dbStatus = jdbc.queryForObject(
                "SELECT status FROM errand WHERE id = ?", String.class, id);
        assertEquals("LOCKED", dbStatus, "DB 是新值、缓存是旧值——这就是缓存与 DB 不一致");
    }
}
