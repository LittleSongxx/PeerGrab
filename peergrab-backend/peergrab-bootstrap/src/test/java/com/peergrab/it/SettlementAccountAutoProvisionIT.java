package com.peergrab.it;

import com.peergrab.application.usecase.*;
import com.peergrab.domain.errand.model.ErrandType;
import com.peergrab.domain.wallet.model.AccountType;
import com.peergrab.domain.wallet.ports.WalletRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 账户缺失自愈：从未开通钱包的跑腿抢单后必须自动获得收款账户；
 * 历史数据缺失账户时，结算前也必须幂等补建而不是让任务永久卡在 DELIVERED。
 */
@SpringBootTest(properties = {
        "peergrab.mq.enabled=false",
        "peergrab.settle.commission-rate=0.05"
})
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "peergrab.it", matches = "true")
class SettlementAccountAutoProvisionIT {

    private static final long PUBLISHER = 1001L;
    private static final long RUNNER = 7001L;
    private static final long BASE = 960_100_000_000L;

    @Autowired PublishErrandUseCase publishUseCase;
    @Autowired GrabErrandUseCase grabUseCase;
    @Autowired ConfirmErrandUseCase confirmUseCase;
    @Autowired PickUpErrandUseCase pickUpUseCase;
    @Autowired DeliverErrandUseCase deliverUseCase;
    @Autowired SettleErrandUseCase settleUseCase;
    @Autowired WalletRepository walletRepository;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    static void requireMiddleware() {
        MiddlewareAvailable.require();
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM fund_event_outbox WHERE errand_id >= ?", BASE);
        jdbc.update("DELETE FROM wallet_ledger WHERE ref_id >= ?", BASE);
        jdbc.update("DELETE FROM escrow_order WHERE errand_id >= ?", BASE);
        jdbc.update("DELETE FROM errand WHERE id >= ?", BASE);
        jdbc.update("DELETE FROM wallet_account WHERE owner_id = ? AND owner_type = 'USER'", RUNNER);
        jdbc.update("UPDATE wallet_account SET available = 100000, frozen = 0 WHERE owner_id = ? AND owner_type = 'USER'", PUBLISHER);
        jdbc.update("UPDATE wallet_account SET available = 0, frozen = 0 WHERE owner_type = 'ESCROW'");
        jdbc.update("UPDATE wallet_account SET available = 0, frozen = 0 WHERE owner_type = 'COMMISSION'");
    }

    @Test
    @DisplayName("无钱包账户的跑腿抢中即自动开通账户，结算不再中毒")
    void grabProvisionsRunnerWalletAndSettlementSucceeds() {
        var pub = publishUseCase.publish(new PublishErrandUseCase.Command(
                1L, PUBLISHER, ErrandType.DELIVERY, "provision_runner", 1000L, 1));
        long errandId = pub.errandId();

        assertTrue(grabUseCase.grab(new GrabErrandUseCase.Command(
                errandId, RUNNER, UUID.randomUUID().toString())).grabbed());
        assertTrue(walletRepository.findByOwner(RUNNER, AccountType.USER).isPresent(),
                "抢中事务内应幂等开通跑腿收款账户");

        confirmUseCase.confirm(new ConfirmErrandUseCase.Command(errandId, RUNNER));
        pickUpUseCase.pickUp(errandId, RUNNER);
        deliverUseCase.deliver(errandId, RUNNER);

        // 模拟历史脏数据：结算前账户缺失，结算路径必须自愈。
        jdbc.update("DELETE FROM wallet_account WHERE owner_id = ? AND owner_type = 'USER'", RUNNER);

        var result = settleUseCase.settle(errandId, PUBLISHER);
        assertEquals(SettleErrandUseCase.Result.SETTLED, result);
        assertTrue(walletRepository.findByOwner(RUNNER, AccountType.USER).isPresent(),
                "结算前应补建收款账户");
        assertEquals("SETTLED", jdbc.queryForObject(
                "SELECT status FROM errand WHERE id = ?", String.class, errandId));
    }
}
