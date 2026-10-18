package com.peergrab.it;

import com.peergrab.application.usecase.ArbitrateErrandUseCase;
import com.peergrab.application.usecase.GrabErrandUseCase;
import com.peergrab.application.usecase.PublishErrandUseCase;
import com.peergrab.domain.errand.model.ArbitrationFavor;
import com.peergrab.domain.errand.model.ErrandType;
import com.peergrab.shared.BizException;
import com.peergrab.shared.ErrorCode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 仲裁员角色分离：仲裁者不得成为交易当事人，也不得裁决与自己相关的争议。
 */
@SpringBootTest(properties = {"peergrab.mq.enabled=false"})
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "peergrab.it", matches = "true")
class ArbitratorSeparationIT {

    private static final long ARBITRATOR = 9001L;
    private static final long PUBLISHER = 1001L;
    private static final long RUNNER = 2001L;
    private static final long BASE = 960_000_000_000L;

    @Autowired PublishErrandUseCase publishUseCase;
    @Autowired GrabErrandUseCase grabUseCase;
    @Autowired ArbitrateErrandUseCase arbitrateUseCase;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    static void requireMiddleware() {
        MiddlewareAvailable.require();
    }

    @BeforeEach
    void reset() {
        jdbc.update("UPDATE wallet_account SET available = 100000, frozen = 0 WHERE owner_id = ? AND owner_type = 'USER'", PUBLISHER);
        jdbc.update("UPDATE wallet_account SET available = 5000, frozen = 0 WHERE owner_id = ? AND owner_type = 'USER'", RUNNER);
        jdbc.update("UPDATE wallet_account SET available = 0, frozen = 0 WHERE owner_type = 'ESCROW'");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM escrow_order WHERE errand_id >= ?", BASE);
        jdbc.update("DELETE FROM wallet_ledger WHERE ref_id >= ?", BASE);
        jdbc.update("DELETE FROM errand WHERE id >= ?", BASE);
    }

    @Test
    @DisplayName("仲裁员不能发布任务")
    void arbitratorCannotPublish() {
        BizException ex = assertThrows(BizException.class, () -> publishUseCase.publish(
                new PublishErrandUseCase.Command(1L, ARBITRATOR, ErrandType.DELIVERY,
                        "arbitrator_publish", 1000L, 1, UUID.randomUUID().toString())));
        assertEquals(ErrorCode.ROLE_NOT_ALLOWED, ex.code());
    }

    @Test
    @DisplayName("仲裁员不能抢单")
    void arbitratorCannotGrab() {
        var pub = publishUseCase.publish(new PublishErrandUseCase.Command(
                1L, PUBLISHER, ErrandType.DELIVERY, "arbitrator_grab", 1000L, 1));
        var result = grabUseCase.grab(new GrabErrandUseCase.Command(
                pub.errandId(), ARBITRATOR, UUID.randomUUID().toString()));
        assertFalse(result.grabbed());
        assertEquals(ErrorCode.ROLE_NOT_ALLOWED, result.code());
    }

    @Test
    @DisplayName("仲裁员不能裁决自己是当事人的争议")
    void arbitratorCannotJudgeOwnDispute() {
        long errandId = BASE + 7;
        jdbc.update("""
                INSERT INTO errand (id, campus_id, publisher_id, grabber_id, type, title,
                                    reward_amount, slot_total, slot_taken, status, round, version)
                VALUES (?, 1, ?, ?, 'DELIVERY', 'self_arbitration', 1000, 1, 1, 'DISPUTED', 1, 9)
                """, errandId, PUBLISHER, ARBITRATOR);
        jdbc.update("""
                INSERT INTO escrow_order (id, campus_id, errand_id, publisher_id, amount, status)
                VALUES (?, 1, ?, ?, 1000, 'HELD')
                """, BASE + 8, errandId, PUBLISHER);

        BizException ex = assertThrows(BizException.class, () ->
                arbitrateUseCase.arbitrate(errandId, ArbitrationFavor.RUNNER, ARBITRATOR));
        assertEquals(ErrorCode.ROLE_NOT_ALLOWED, ex.code());
    }
}
