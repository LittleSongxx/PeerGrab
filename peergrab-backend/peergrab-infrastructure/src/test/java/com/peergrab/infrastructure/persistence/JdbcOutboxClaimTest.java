package com.peergrab.infrastructure.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class JdbcOutboxClaimTest {

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:outbox_claim_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("""
                CREATE TABLE local_message (
                    id BIGINT PRIMARY KEY, msg_key VARCHAR(96), topic VARCHAR(64), payload VARCHAR(512),
                    deliver_at TIMESTAMP, status VARCHAR(16), next_retry_at TIMESTAMP,
                    retry_count INT, claim_token CHAR(36), claim_until TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE fund_event_outbox (
                    biz_no VARCHAR(64) PRIMARY KEY, event_type VARCHAR(32), errand_id BIGINT,
                    publisher_id BIGINT, runner_id BIGINT, amount_cents BIGINT,
                    commission_cents BIGINT, status VARCHAR(16), next_retry_at TIMESTAMP,
                    retry_count INT, claim_token CHAR(36), claim_until TIMESTAMP)
                """);
    }

    @Test
    void localMessageHasOneOwnerAndStaleOwnerCannotChangeSentRow() {
        jdbc.update("""
                INSERT INTO local_message VALUES (1, 'timeout:1:0', 'timeout', '{}', NOW(),
                    'PENDING', NOW(), 0, NULL, NULL)
                """);
        var workerA = new JdbcLocalMessageRepository(jdbc);
        var workerB = new JdbcLocalMessageRepository(jdbc);
        var claim = workerA.claimPending(10).getFirst();
        assertTrue(workerB.claimPending(10).isEmpty());
        assertFalse(workerB.markClaimedRetry("timeout:1:0", "stale-token", 100));
        assertTrue(workerA.markClaimedSent("timeout:1:0", claim.claimToken()));
        workerB.markRetry("timeout:1:0", 100);
        assertEquals("SENT", jdbc.queryForObject(
                "SELECT status FROM local_message WHERE id=1", String.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT retry_count FROM local_message WHERE id=1", Integer.class));
    }

    @Test
    void fundEventHasOneOwnerAndExpiredClaimCanBeRecovered() {
        jdbc.update("""
                INSERT INTO fund_event_outbox VALUES ('settle:1', 'SETTLED', 1, 2, 3, 100, 5,
                    'PENDING', NOW(), 0, NULL, NULL)
                """);
        var workerA = new JdbcFundEventOutboxRepository(jdbc);
        var workerB = new JdbcFundEventOutboxRepository(jdbc);
        var first = workerA.claimPending(10).getFirst();
        assertTrue(workerB.claimPending(10).isEmpty());
        jdbc.update("UPDATE fund_event_outbox SET claim_until = DATEADD('SECOND', -1, NOW())");
        var second = workerB.claimPending(10).getFirst();
        assertNotEquals(first.claimToken(), second.claimToken());
        assertFalse(workerA.markClaimedSent("settle:1", first.claimToken()));
        assertTrue(workerB.markClaimedSent("settle:1", second.claimToken()));
        assertEquals("SENT", jdbc.queryForObject(
                "SELECT status FROM fund_event_outbox WHERE biz_no='settle:1'", String.class));
    }

    @Test
    void concurrentWorkersCanClaimEachLocalMessageOnce() throws Exception {
        jdbc.update("""
                INSERT INTO local_message VALUES (2, 'timeout:2:0', 'timeout', '{}', NOW(),
                    'PENDING', NOW(), 0, NULL, NULL)
                """);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> {
                ready.countDown();
                start.await();
                return new JdbcLocalMessageRepository(jdbc).claimPending(10);
            });
            var b = pool.submit(() -> {
                ready.countDown();
                start.await();
                return new JdbcLocalMessageRepository(jdbc).claimPending(10);
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1, a.get(5, TimeUnit.SECONDS).size() + b.get(5, TimeUnit.SECONDS).size());
        }
    }
}
