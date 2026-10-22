package com.peergrab.it;

import com.peergrab.application.usecase.*;
import com.peergrab.domain.errand.model.Errand;
import com.peergrab.domain.errand.model.ErrandType;
import com.peergrab.domain.errand.ports.ErrandRepository;
import com.peergrab.domain.recon.ports.ReconRepository;
import com.peergrab.domain.wallet.model.AccountType;
import com.peergrab.domain.wallet.ports.WalletRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 批量自动结算的真实数据库验证：v5 的单事务批量过账（一次锁账户 + 批量更新 +
 * 批量写流水 + 更新后回读校验）必须与单笔结算产生完全一致的账务结果。
 */
@SpringBootTest(properties = {
        "peergrab.mq.enabled=false",
        "peergrab.settle.commission-rate=0.05",
        "peergrab.settle.scan-batch-size=10"
})
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "peergrab.it", matches = "true")
class AutoSettleBatchIT {

    private static final long PUBLISHER = 1001L;
    private static final long RUNNER = 2001L;
    private static final long REWARD = 2000L;

    @Autowired PublishErrandUseCase publishUseCase;
    @Autowired GrabErrandUseCase grabUseCase;
    @Autowired ConfirmErrandUseCase confirmUseCase;
    @Autowired PickUpErrandUseCase pickUpUseCase;
    @Autowired DeliverErrandUseCase deliverUseCase;
    @Autowired SettleErrandUseCase settleUseCase;
    @Autowired WalletRepository walletRepository;
    @Autowired ReconRepository reconRepository;
    @Autowired ErrandRepository errandRepository;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    static void requireMiddleware() {
        MiddlewareAvailable.require();
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM fund_event_outbox");
        jdbc.update("DELETE FROM wallet_ledger");
        jdbc.update("DELETE FROM escrow_order");
        jdbc.update("UPDATE wallet_account SET available = 100000, frozen = 0 WHERE owner_id = ? AND owner_type = 'USER'", PUBLISHER);
        jdbc.update("UPDATE wallet_account SET available = 5000, frozen = 0 WHERE owner_id = ? AND owner_type = 'USER'", RUNNER);
        jdbc.update("DELETE FROM wallet_account WHERE owner_type IN ('ESCROW','COMMISSION')");
        jdbc.update("DELETE FROM recon_diff");
    }

    @Test
    @DisplayName("批量自动结算与单笔结算账务一致，重复调用返回 skipped 不重复入账")
    void batchSettlementMatchesSingleSettlementAndIsIdempotent() {
        long first = deliverDueErrand();
        long second = deliverDueErrand();
        var due = List.of(errandRepository.findById(first).orElseThrow(),
                errandRepository.findById(second).orElseThrow());

        var result = settleUseCase.settleAutoBatch(due);

        assertEquals(new SettleErrandUseCase.BatchResult(2, 0), result);
        for (long id : List.of(first, second)) {
            assertEquals("SETTLED", jdbc.queryForObject(
                    "SELECT status FROM errand WHERE id = ?", String.class, id));
            assertEquals(1, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM escrow_order WHERE errand_id = ? AND status = 'RELEASED'
                    """, Integer.class, id));
            assertEquals(3, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM wallet_ledger WHERE biz_no = ?", Integer.class, "settle:" + id));
        }

        // 每单 2000 分、佣金 5%：跑腿净得 1900，佣金 100
        long runnerCents = walletRepository.findByOwner(RUNNER, AccountType.USER).orElseThrow().available().cents();
        assertEquals(5000L + 2 * 1900L, runnerCents, "批量结算必须给跑腿入账两笔净额");
        long commission = 0L;
        long escrow = 0L;
        for (long id : List.of(first, second)) {
            commission += walletRepository.findByOwner(id, AccountType.COMMISSION).orElseThrow().available().cents();
            escrow += walletRepository.findByOwner(id, AccountType.ESCROW).orElseThrow().available().cents();
        }
        assertEquals(2 * 100L, commission);
        assertEquals(0L, escrow, "批量结算后每单托管账户必须清零");

        assertEquals(0L, reconRepository.debitMinusCredit(), "L1 借贷平衡");
        assertTrue(reconRepository.findSnapshotDiffs().isEmpty(), "L2 系统户快照一致");
        assertTrue(reconRepository.findEscrowClosureDiffs().isEmpty(), "L3 托管闭环");

        var replay = settleUseCase.settleAutoBatch(List.of(
                errandRepository.findById(first).orElseThrow(),
                errandRepository.findById(second).orElseThrow()));
        assertEquals(new SettleErrandUseCase.BatchResult(0, 2), replay, "已结算任务不得重复入账");
    }

    private long deliverDueErrand() {
        var pub = publishUseCase.publish(new PublishErrandUseCase.Command(
                1L, PUBLISHER, ErrandType.DELIVERY, "batch_settle_" + UUID.randomUUID(), REWARD, 1));
        long errandId = pub.errandId();
        grabUseCase.grab(new GrabErrandUseCase.Command(errandId, RUNNER, UUID.randomUUID().toString()));
        confirmUseCase.confirm(new ConfirmErrandUseCase.Command(errandId, RUNNER));
        pickUpUseCase.pickUp(errandId, RUNNER);
        deliverUseCase.deliver(errandId, RUNNER);
        jdbc.update("""
                UPDATE errand
                   SET delivered_at = DATE_SUB(NOW(3), INTERVAL 2 HOUR),
                       auto_settle_deadline_at = DATE_SUB(NOW(3), INTERVAL 1 SECOND)
                 WHERE id = ?
                """, errandId);
        Errand reloaded = errandRepository.findById(errandId).orElseThrow();
        assertEquals("DELIVERED", reloaded.status().name());
        return errandId;
    }
}
