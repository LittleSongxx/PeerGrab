package com.peergrab.it;

import com.peergrab.application.usecase.GrabErrandUseCase;
import com.peergrab.application.usecase.PublishErrandUseCase;
import com.peergrab.domain.errand.model.ErrandType;
import com.peergrab.shared.ErrorCode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"peergrab.credit.max-ongoing=2", "peergrab.mq.enabled=false"})
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "peergrab.it", matches = "true")
class RunnerQuotaIT {

    @Autowired PublishErrandUseCase publish;
    @Autowired GrabErrandUseCase grab;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    static void requireMiddleware() {
        Assumptions.assumeTrue(MiddlewareAvailable.check(), "中间件未启动，跳过");
    }

    @BeforeEach
    void ensureFundsAndMigration() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS runner_quota_lock (
                  runner_id BIGINT NOT NULL PRIMARY KEY
                ) ENGINE=InnoDB
                """);
        jdbc.update("UPDATE wallet_account SET available = 100000, frozen = 0 WHERE owner_id = 1001 AND owner_type = 'USER'");
    }

    @Test
    void locked_tasks_count_before_confirmation() {
        long runner = System.currentTimeMillis();
        long[] errands = publishThree();

        assertTrue(grab(errands[0], runner).grabbed());
        assertTrue(grab(errands[1], runner).grabbed());
        var third = grab(errands[2], runner);

        assertEquals(ErrorCode.TOO_MANY_ONGOING, third.code());
        assertEquals(2, countLocked(runner));
    }

    @Test
    void concurrent_grabs_cannot_exceed_quota() throws Exception {
        long runner = System.currentTimeMillis();
        long[] errands = publishThree();
        var start = new CountDownLatch(1);
        List<GrabErrandUseCase.Result> results = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<GrabErrandUseCase.Result>>();
            for (long errand : errands) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return grab(errand, runner);
                }));
            }
            start.countDown();
            for (var future : futures) results.add(future.get(30, TimeUnit.SECONDS));
        }

        assertEquals(2, results.stream().filter(GrabErrandUseCase.Result::grabbed).count());
        assertEquals(1, results.stream().filter(r -> r.code() == ErrorCode.TOO_MANY_ONGOING).count());
        assertEquals(2, countLocked(runner));
    }

    private long[] publishThree() {
        long[] ids = new long[3];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = publish.publish(new PublishErrandUseCase.Command(
                    1L, 1001L, ErrandType.DELIVERY, "额度并发测试" + i, 100L, 1)).errandId();
        }
        return ids;
    }

    private GrabErrandUseCase.Result grab(long errandId, long runnerId) {
        return grab.grab(new GrabErrandUseCase.Command(errandId, runnerId, UUID.randomUUID().toString()));
    }

    private int countLocked(long runnerId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM errand
                 WHERE grabber_id = ? AND status IN ('LOCKED', 'ACCEPTED', 'PICKED_UP')
                """, Integer.class, runnerId);
        return n == null ? 0 : n;
    }
}
