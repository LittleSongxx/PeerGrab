package com.peergrab.it;

import com.peergrab.application.usecase.PublishErrandUseCase;
import com.peergrab.domain.errand.model.ErrandType;
import com.peergrab.shared.BizException;
import com.peergrab.shared.ErrorCode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Concurrent requests prove that the durable claim is made before any escrow transfer. */
@SpringBootTest(properties = "peergrab.mq.enabled=false")
@EnabledIfSystemProperty(named = "peergrab.it", matches = "true")
class PublishIdempotencyIT {

    @Autowired PublishErrandUseCase publish;
    @Autowired JdbcTemplate jdbc;

    @Test
    void samePublisherAndKeyCreateAndChargeExactlyOnce() throws Exception {
        Assumptions.assumeTrue(MiddlewareAvailable.check(), "integration middleware unavailable");
        long publisher = ThreadLocalRandom.current().nextLong(6_000_000_000L, 7_000_000_000L);
        long anotherPublisher = publisher + 1_000_000_000L;
        createAccount(publisher);
        createAccount(anotherPublisher);
        String key = "publish-idem-" + UUID.randomUUID();
        PublishErrandUseCase.Command command = new PublishErrandUseCase.Command(
                1, publisher, ErrandType.DELIVERY, "publish idempotency", 100, 1, key);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        Set<Long> ids = new HashSet<>();
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return publish.publish(command).errandId();
                }));
            }
            start.countDown();
            for (Future<Long> future : futures) ids.add(future.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, ids.size(), "all same-key requests must replay one task ID");
        long errandId = ids.iterator().next();
        assertEquals(errandId, publish.publish(command).errandId(),
                "a later retry after an ambiguous response must recover the original ID");
        assertEquals(1, count("SELECT COUNT(*) FROM publish_request WHERE publisher_id = ? AND request_id = ?",
                publisher, key));
        assertEquals(1, count("SELECT COUNT(*) FROM errand WHERE publisher_id = ?", publisher));
        assertEquals(2, count("SELECT COUNT(*) FROM wallet_ledger WHERE biz_no = ?",
                "escrow:" + errandId));
        assertEquals(9_900L, balance(publisher), "reward is deducted once");

        BizException conflict = assertThrows(BizException.class, () -> publish.publish(
                new PublishErrandUseCase.Command(1, publisher, ErrandType.DELIVERY,
                        "different title", 100, 1, key)));
        assertEquals(ErrorCode.DUPLICATE_REQUEST, conflict.code());
        assertEquals(9_900L, balance(publisher));

        long independent = publish.publish(new PublishErrandUseCase.Command(
                1, anotherPublisher, ErrandType.DELIVERY,
                "publish idempotency", 100, 1, key)).errandId();
        assertNotEquals(errandId, independent, "the key is scoped to the authenticated publisher");
        assertEquals(9_900L, balance(anotherPublisher));
    }

    private void createAccount(long userId) {
        jdbc.update("""
                INSERT INTO wallet_account (id, owner_id, owner_type, available, frozen, version)
                VALUES (?, ?, 'USER', 10000, 0, 0)
                """, userId, userId);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private long balance(long userId) {
        return jdbc.queryForObject("SELECT available FROM wallet_account WHERE owner_id = ?",
                Long.class, userId);
    }
}
