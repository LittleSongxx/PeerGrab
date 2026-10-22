package com.peergrab.it;

import com.peergrab.application.usecase.ConfirmErrandUseCase;
import com.peergrab.application.usecase.DeliverErrandUseCase;
import com.peergrab.application.usecase.GrabErrandUseCase;
import com.peergrab.application.usecase.PickUpErrandUseCase;
import com.peergrab.application.usecase.PublishErrandUseCase;
import com.peergrab.application.usecase.RefundErrandUseCase;
import com.peergrab.application.usecase.SettleErrandUseCase;
import com.peergrab.domain.errand.model.ErrandType;
import com.peergrab.domain.recon.ports.ReconRepository;
import com.peergrab.domain.wallet.model.AccountType;
import com.peergrab.domain.wallet.ports.WalletRepository;
import com.peergrab.shared.BizException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 每任务独立 ESCROW/COMMISSION 账户的端到端验证：
 * 发布开户 → 结算只动本任务账户 → 退款只退本任务托管户 → 对账 L1/L2/L3 全绿。
 */
@SpringBootTest(properties = {
        "peergrab.mq.enabled=false",
        "peergrab.settle.commission-rate=0.05"
})
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "peergrab.it", matches = "true")
class PerErrandWalletTopologyIT {

    private static final long PUBLISHER = 1001L;
    private static final long RUNNER = 2001L;

    @Autowired PublishErrandUseCase publishUseCase;
    @Autowired GrabErrandUseCase grabUseCase;
    @Autowired ConfirmErrandUseCase confirmUseCase;
    @Autowired PickUpErrandUseCase pickUpUseCase;
    @Autowired DeliverErrandUseCase deliverUseCase;
    @Autowired SettleErrandUseCase settleUseCase;
    @Autowired RefundErrandUseCase refundUseCase;
    @Autowired WalletRepository walletRepository;
    @Autowired ReconRepository reconRepository;
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
        jdbc.update("DELETE FROM wallet_account WHERE owner_type IN ('ESCROW','COMMISSION')");
        jdbc.update("UPDATE wallet_account SET available = 100000, frozen = 0, version = 0 "
                + "WHERE owner_id = ? AND owner_type = 'USER'", PUBLISHER);
        jdbc.update("UPDATE wallet_account SET available = 5000, frozen = 0, version = 0 "
                + "WHERE owner_id = ? AND owner_type = 'USER'", RUNNER);
        jdbc.update("DELETE FROM recon_diff");
    }

    @Test
    @DisplayName("发布创建本任务 ESCROW/COMMISSION，结算只影响本任务账户")
    void publishCreatesPerErrandAccountsAndSettleUsesThem() {
        long errandId = publish();
        var escrow = walletRepository.findByOwner(errandId, AccountType.ESCROW).orElseThrow();
        var commission = walletRepository.findByOwner(errandId, AccountType.COMMISSION).orElseThrow();
        assertEquals(2000L, escrow.available().cents(), "托管户余额等于悬赏");
        assertEquals(0L, commission.available().cents(), "佣金户初始为零");
        assertTrue(walletRepository.findByOwner(-1L, AccountType.ESCROW).isEmpty(),
                "新模型不得依赖共享 -1 托管户");
        assertTrue(walletRepository.findByOwner(-2L, AccountType.COMMISSION).isEmpty(),
                "新模型不得依赖共享 -2 佣金户");

        grabUseCase.grab(new GrabErrandUseCase.Command(errandId, RUNNER, UUID.randomUUID().toString()));
        confirmUseCase.confirm(new ConfirmErrandUseCase.Command(errandId, RUNNER));
        pickUpUseCase.pickUp(errandId, RUNNER);
        deliverUseCase.deliver(errandId, RUNNER);
        assertEquals(SettleErrandUseCase.Result.SETTLED, settleUseCase.settle(errandId, PUBLISHER));

        assertEquals(0L, walletRepository.findByOwner(errandId, AccountType.ESCROW).orElseThrow()
                .available().cents(), "结算后本任务托管户清零");
        assertEquals(100L, walletRepository.findByOwner(errandId, AccountType.COMMISSION).orElseThrow()
                .available().cents(), "佣金只进入本任务佣金户");
        assertEquals(5000L + 1900L, walletRepository.findByOwner(RUNNER, AccountType.USER).orElseThrow()
                .available().cents(), "跑腿收到 1900");
        assertEquals(0L, reconRepository.debitMinusCredit(), "L1 借贷平衡");
        assertTrue(reconRepository.findSnapshotDiffs().isEmpty(), "L2 每单账户快照一致");
        assertTrue(reconRepository.findEscrowClosureDiffs().isEmpty(), "L3 托管闭环");
    }

    @Test
    @DisplayName("退款只退本任务托管户，账户余额归零且不碰其他任务")
    void refundUsesOnlyTheTaskEscrow() {
        long refunded = publish();
        long untouched = publish();
        assertEquals(RefundErrandUseCase.Result.REFUNDED,
                refundUseCase.cancelAndRefund(refunded, PUBLISHER));

        assertEquals(0L, walletRepository.findByOwner(refunded, AccountType.ESCROW).orElseThrow()
                .available().cents());
        assertEquals(2000L, walletRepository.findByOwner(untouched, AccountType.ESCROW).orElseThrow()
                .available().cents(), "另一任务的托管户不受影响");
        assertEquals(98000L, walletRepository.findByOwner(PUBLISHER, AccountType.USER).orElseThrow()
                .available().cents(), "退掉一个任务后，发单人余额=100000-2000");
        assertTrue(reconRepository.findSnapshotDiffs().isEmpty());
    }

    @Test
    @DisplayName("缺失每单账户时明确失败，而不是回退共享户")
    void missingPerErrandAccountFailsClosed() {
        long errandId = publish();
        jdbc.update("DELETE FROM wallet_account WHERE owner_id = ? AND owner_type = 'ESCROW'", errandId);

        grabUseCase.grab(new GrabErrandUseCase.Command(errandId, RUNNER, UUID.randomUUID().toString()));
        confirmUseCase.confirm(new ConfirmErrandUseCase.Command(errandId, RUNNER));
        pickUpUseCase.pickUp(errandId, RUNNER);
        deliverUseCase.deliver(errandId, RUNNER);

        BizException error = assertThrows(BizException.class,
                () -> settleUseCase.settle(errandId, PUBLISHER));
        assertEquals(com.peergrab.shared.ErrorCode.ACCOUNT_NOT_FOUND, error.code());
    }

    private long publish() {
        return publishUseCase.publish(new PublishErrandUseCase.Command(
                1L, PUBLISHER, ErrandType.DELIVERY, "topology_" + UUID.randomUUID(), 2000L, 1)).errandId();
    }
}
