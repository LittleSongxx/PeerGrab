package com.peergrab.it;

import com.peergrab.application.usecase.PublishErrandUseCase;
import com.peergrab.application.usecase.RefundErrandUseCase;
import com.peergrab.domain.errand.model.ErrandType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL test for account lock ordering and ledger balance/version chains. */
@SpringBootTest(properties = "peergrab.mq.enabled=false")
@EnabledIfSystemProperty(named = "peergrab.it", matches = "true")
class WalletLedgerConcurrencyIT {

    @Autowired PublishErrandUseCase publish;
    @Autowired RefundErrandUseCase refund;
    @Autowired JdbcTemplate jdbc;

    private record LedgerRow(String direction, long amount, long after, long version) {}

    @Test
    void concurrentPublishAndRefundKeepEveryAccountLedgerOrdered() throws Exception {
        Assumptions.assumeTrue(MiddlewareAvailable.check(), "integration middleware unavailable");
        long userId = ThreadLocalRandom.current().nextLong(8_000_000_000L, 9_000_000_000L);
        long openingUser = 1_000_000L;
        long openingEscrow = balance(1);
        long openingEscrowVersion = version(1);
        jdbc.update("""
                INSERT INTO wallet_account (id, owner_id, owner_type, available, frozen, version)
                VALUES (?, ?, 'USER', ?, 0, 0)
                """, userId, userId, openingUser);

        List<Long> existing = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            existing.add(publish.publish(new PublishErrandUseCase.Command(
                    1, userId, ErrandType.DELIVERY, "ledger_concurrency_seed", 100, 1)).errandId());
        }

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (long errandId : existing) {
                futures.add(pool.submit(() -> {
                    await(start);
                    assertEquals(RefundErrandUseCase.Result.REFUNDED,
                            refund.cancelAndRefund(errandId, userId));
                }));
            }
            for (int i = 0; i < 12; i++) {
                futures.add(pool.submit(() -> {
                    await(start);
                    publish.publish(new PublishErrandUseCase.Command(
                            1, userId, ErrandType.BUY, "ledger_concurrency_new", 100, 1));
                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertAccountChain(userId, openingUser, 0, 36);
        assertAccountChain(1, openingEscrow, openingEscrowVersion, 36);
        assertEquals(openingUser - 1_200, balance(userId));
        assertEquals(openingEscrow + 1_200, balance(1));
    }

    private void assertAccountChain(long accountId, long opening, long openingVersion, int count) {
        List<LedgerRow> rows = jdbc.query("""
                SELECT direction, amount, balance_after, account_version
                  FROM wallet_ledger
                 WHERE account_id = ? AND account_version > ?
                   AND account_version <= ?
                 ORDER BY account_version
                """, (rs, rowNum) -> new LedgerRow(rs.getString(1), rs.getLong(2),
                rs.getLong(3), rs.getLong(4)), accountId, openingVersion, openingVersion + count);
        assertEquals(count, rows.size(), "one new version per committed ledger row, account=" + accountId);
        long running = opening;
        for (int i = 0; i < rows.size(); i++) {
            LedgerRow row = rows.get(i);
            assertEquals(openingVersion + i + 1, row.version());
            running += row.direction().equals("CREDIT") ? row.amount() : -row.amount();
            assertEquals(running, row.after(), "ledger balance chain account=" + accountId);
        }
        assertEquals(running, balance(accountId));
        assertEquals(openingVersion + count, version(accountId));
    }

    private long balance(long accountId) {
        return jdbc.queryForObject("SELECT available FROM wallet_account WHERE id = ?",
                Long.class, accountId);
    }

    private long version(long accountId) {
        return jdbc.queryForObject("SELECT version FROM wallet_account WHERE id = ?",
                Long.class, accountId);
    }

    private static void await(CountDownLatch gate) {
        try {
            gate.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
