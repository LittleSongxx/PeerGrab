package com.peergrab.it;

import com.peergrab.application.usecase.PublishErrandUseCase;
import com.peergrab.application.usecase.SettleErrandUseCase;
import com.peergrab.domain.errand.model.ErrandType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Money must commit with its outbox record even when the configured broker cannot be reached. */
@SpringBootTest(properties = {
        "peergrab.mq.enabled=true",
        "peergrab.mq.endpoints=127.0.0.1:39999"
})
@EnabledIfSystemProperty(named = "peergrab.it", matches = "true")
class FundOutboxWithoutBrokerIT {

    @Autowired PublishErrandUseCase publish;
    @Autowired SettleErrandUseCase settle;
    @Autowired JdbcTemplate jdbc;

    @Test
    void settlementCommitsAndEventRemainsPendingWhenBrokerIsDown() {
        Assumptions.assumeTrue(MiddlewareAvailable.check(), "isolated MySQL/Redis required");
        long publisher = 8_000_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 500_000_000L);
        long runner = publisher + 500_000_000L;
        jdbc.update("""
                INSERT INTO wallet_account (id, owner_id, owner_type, available, frozen, version)
                VALUES (?, ?, 'USER', 10000, 0, 0), (?, ?, 'USER', 0, 0, 0)
                """, publisher, publisher, runner, runner);

        long errandId = publish.publish(new PublishErrandUseCase.Command(
                1, publisher, ErrandType.DELIVERY, "outbox broker outage " + UUID.randomUUID(),
                1000, 1)).errandId();
        // This test isolates the funds boundary; fulfillment transitions have their own tests.
        jdbc.update("""
                UPDATE errand SET status = 'DELIVERED', grabber_id = ?, slot_taken = 1,
                                  delivered_at = NOW(3), version = version + 1
                 WHERE id = ?
                """, runner, errandId);

        assertEquals(SettleErrandUseCase.Result.SETTLED, settle.settle(errandId, publisher));
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM fund_event_outbox WHERE biz_no = ?", String.class,
                "settle:" + errandId));
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM wallet_ledger WHERE biz_no = ?", Integer.class,
                "settle:" + errandId));
    }
}
