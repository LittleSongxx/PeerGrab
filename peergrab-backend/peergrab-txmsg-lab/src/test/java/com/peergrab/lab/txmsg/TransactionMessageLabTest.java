package com.peergrab.lab.txmsg;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事务消息 lab 的可执行规格：半消息可见性、commit/rollback、三态回查与发送失败短路。
 *
 * <p>真实 RocketMQ 5.x 映射：
 * <ul>
 *   <li>生产者通过 {@code ProducerBuilder#setTransactionChecker(TransactionChecker)} 注册回查器；
 *       发送半消息后由应用执行本地事务，再显式 {@code Transaction#commit()} / {@code rollback()}。</li>
 *   <li>本地事务结果未知时 Broker 回查，回查器返回
 *       {@code TransactionResolution.COMMIT / ROLLBACK / UNKNOWN}；
 *       真实 Broker {@code transactionCheckInterval} 默认 60s、最多回查 15 次。</li>
 *   <li>回查只允许读“只增不减的账本事实”（如 {@code wallet_ledger.biz_no}），
 *       不得读会被超时、改派、重试改写的 {@code errand.status} 一类可变状态，
 *       否则一次状态抖动就可能把已经成功的资金事务回滚掉。</li>
 * </ul>
 *
 * <p>PeerGrab 生产仍走本地消息表/outbox（{@code fund_event_outbox}）：
 * 自动结算需要延迟投递，而事务消息与定时消息互斥；outbox 还让“MQ 不可用”不阻塞
 * 资金事务提交。这个 lab 只用于对照实验与讲解，不进入 API/worker 运行包。
 */
class TransactionMessageLabTest {

    @Test
    void committedMessageBecomesVisibleExactlyOnce() {
        InMemoryTransactionBroker broker = new InMemoryTransactionBroker();

        TransactionMessage half = broker.beginTransaction(message("BIZ-COMMIT"));

        assertEquals(TransactionMessageStatus.PREPARED, half.status());
        assertTrue(broker.visibleMessages().isEmpty(), "半消息对消费者不可见");
        assertTrue(broker.pollVisible().isEmpty(), "半消息不能投递");

        assertTrue(broker.commit("BIZ-COMMIT"));
        assertFalse(broker.commit("BIZ-COMMIT"), "重复 commit 必须幂等");

        assertEquals(TransactionMessageStatus.COMMITTED, broker.statusOf("BIZ-COMMIT"));
        assertEquals(1, broker.visibleMessages().size(), "commit 只产生一条投递");
        TransactionMessage delivered = broker.pollVisible().orElseThrow();
        assertEquals("BIZ-COMMIT", delivered.bizNo());
        assertEquals(TransactionMessageStatus.COMMITTED, delivered.status());
        assertTrue(broker.pollVisible().isEmpty(), "同一条消息只能投递一次");
    }

    @Test
    void rolledBackMessageIsNeverVisible() {
        InMemoryTransactionBroker broker = new InMemoryTransactionBroker();
        broker.beginTransaction(message("BIZ-ROLLBACK"));

        assertTrue(broker.rollback("BIZ-ROLLBACK"));
        assertFalse(broker.rollback("BIZ-ROLLBACK"), "重复 rollback 必须幂等");

        assertEquals(TransactionMessageStatus.ROLLED_BACK, broker.statusOf("BIZ-ROLLBACK"));
        assertTrue(broker.visibleMessages().isEmpty(), "回滚消息不可见");
        assertTrue(broker.pollVisible().isEmpty(), "回滚消息不可投递");
        assertFalse(broker.commit("BIZ-ROLLBACK"), "回滚后不能再提交");

        AtomicBoolean checkerCalled = new AtomicBoolean(false);
        TransactionResolution resolution = broker.checkBack("BIZ-ROLLBACK", message -> {
            checkerCalled.set(true);
            return TransactionResolution.COMMIT;
        });
        assertEquals(TransactionResolution.ROLLBACK, resolution);
        assertFalse(checkerCalled.get(), "已回滚的事务不应再回查");
    }

    @Test
    void checkBackCommitsWhenLedgerFactExistsEvenIfCommitCallWasLost() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-19T00:00:00Z"));
        InMemoryTransactionBroker broker = new InMemoryTransactionBroker(clock);
        Set<String> ledgerFacts = new HashSet<>();

        broker.beginTransaction(message("BIZ-LOST-COMMIT"));
        // 本地事务成功：账本流水已落库；但 producer 的 commit RPC 丢失，Broker 仍停留在 PREPARED。
        ledgerFacts.add("BIZ-LOST-COMMIT");
        clock.advance(Duration.ofSeconds(5));

        TransactionResolution resolution = broker.checkBack(
                "BIZ-LOST-COMMIT", new LedgerFactChecker(ledgerFacts::contains));

        assertEquals(TransactionResolution.COMMIT, resolution);
        assertEquals(TransactionMessageStatus.COMMITTED, broker.statusOf("BIZ-LOST-COMMIT"));
        assertEquals(1, broker.visibleMessages().size(), "回查提交后只投递一次");
        assertTrue(broker.pollVisible().isPresent());
        assertTrue(broker.pollVisible().isEmpty(), "回查提交也不能重复投递");
    }

    @Test
    void checkBackStaysUnknownWithinGraceThenRollsBackAfterGrace() {
        Instant start = Instant.parse("2026-10-19T00:00:00Z");
        MutableClock clock = new MutableClock(start);
        InMemoryTransactionBroker broker = new InMemoryTransactionBroker(clock);
        LedgerFactChecker checker = new LedgerFactChecker(bizNo -> false);

        broker.beginTransaction(message("BIZ-SLOW-LOCAL-TX"));

        clock.advance(Duration.ofSeconds(30));
        assertEquals(TransactionResolution.UNKNOWN,
                broker.checkBack("BIZ-SLOW-LOCAL-TX", checker), "60s 宽限内必须继续等待");
        assertEquals(TransactionMessageStatus.PREPARED, broker.statusOf("BIZ-SLOW-LOCAL-TX"));
        assertTrue(broker.visibleMessages().isEmpty());

        clock.advance(Duration.ofSeconds(31)); // 累计 61s，超过 60s 宽限
        assertEquals(TransactionResolution.ROLLBACK,
                broker.checkBack("BIZ-SLOW-LOCAL-TX", checker), "超宽限仍无账本事实则放弃");
        assertEquals(TransactionMessageStatus.DISCARDED, broker.statusOf("BIZ-SLOW-LOCAL-TX"));
        assertTrue(broker.visibleMessages().isEmpty(), "丢弃的半消息不能可见");
    }

    @Test
    void localTransactionIsNotExecutedWhenHalfMessageSendFails() {
        AtomicBoolean localTransactionExecuted = new AtomicBoolean(false);
        InMemoryTransactionBroker broker = new InMemoryTransactionBroker(
                Clock.systemUTC(), halfMessage -> false);

        assertThrows(InMemoryTransactionBroker.HalfMessageSendException.class,
                () -> broker.sendMessageInTransaction(message("BIZ-HALF-FAIL"), () -> {
                    localTransactionExecuted.set(true);
                    return TransactionResolution.COMMIT;
                }));

        assertFalse(localTransactionExecuted.get(), "半消息失败时本地事务回调必须未执行");
        assertFalse(broker.contains("BIZ-HALF-FAIL"), "失败的半消息不能落盘");
        assertTrue(broker.visibleMessages().isEmpty(), "失败的半消息不能投递");
    }

    @Test
    void checkerOnlyDependsOnInjectedLedgerFactNotMutableMessageStatus() {
        Set<String> ledgerFacts = Set.of("BIZ-FACT");
        AtomicReference<String> queriedBizNo = new AtomicReference<>();
        AtomicInteger queries = new AtomicInteger();
        LedgerFactChecker checker = new LedgerFactChecker(bizNo -> {
            queriedBizNo.set(bizNo);
            queries.incrementAndGet();
            return ledgerFacts.contains(bizNo);
        });

        for (TransactionMessageStatus status : TransactionMessageStatus.values()) {
            TransactionMessage snapshot = new TransactionMessage("BIZ-FACT", "SETTLE", "{}", status);
            assertEquals(TransactionResolution.COMMIT, checker.check(snapshot),
                    "回查只看账本事实，不看消息当前状态: " + status);
        }
        assertEquals("BIZ-FACT", queriedBizNo.get(), "只把 bizNo 交给账本查询");
        assertEquals(TransactionMessageStatus.values().length, queries.get());

        TransactionMessage missingFact = new TransactionMessage(
                "BIZ-NO-FACT", "SETTLE", "{}", TransactionMessageStatus.COMMITTED);
        assertEquals(TransactionResolution.UNKNOWN, checker.check(missingFact),
                "账本事实缺失不能武断 ROLLBACK，应由 Broker 宽限兜底");
    }

    private static TransactionMessage message(String bizNo) {
        return new TransactionMessage(bizNo, "SETTLE", "{\"bizNo\":\"" + bizNo + "\"}",
                TransactionMessageStatus.PREPARED);
    }

    /** 可手动推进的时钟，用于压缩真实 Broker 的 60s 回查宽限。 */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
