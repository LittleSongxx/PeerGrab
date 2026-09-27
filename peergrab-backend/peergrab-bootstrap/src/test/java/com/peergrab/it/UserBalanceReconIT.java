package com.peergrab.it;

import com.peergrab.domain.recon.ports.ReconRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "peergrab.mq.enabled=false")
@EnabledIfSystemProperty(named = "peergrab.it", matches = "true")
class UserBalanceReconIT {

    @Autowired ReconRepository recon;
    @Autowired JdbcTemplate jdbc;

    @Test
    void userSnapshotDriftIsDetectedAgainstLatestOrderedPosting() {
        Assumptions.assumeTrue(MiddlewareAvailable.check(), "isolated MySQL/Redis required");
        long accountId = 8_000_000_000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000_000L);
        long ledgerId = accountId + 1_000_000_000L;
        try {
            jdbc.update("""
                    INSERT INTO wallet_account (id, owner_id, owner_type, available, frozen, version)
                    VALUES (?, ?, 'USER', 150, 0, 1)
                    """, accountId, accountId);
            jdbc.update("""
                    INSERT INTO wallet_ledger (id, biz_no, account_id, user_id, direction,
                                               amount, balance_after, account_version, ref_type, ref_id)
                    VALUES (?, ?, ?, ?, 'CREDIT', 50, 150, 1, 'ESCROW', ?)
                    """, ledgerId, "recon-it:" + accountId, accountId, accountId, accountId);

            assertFalse(recon.findUserBalanceDiffs().stream().anyMatch(d -> d.accountId() == accountId));
            jdbc.update("UPDATE wallet_account SET available = 151 WHERE id = ?", accountId);
            assertTrue(recon.findUserBalanceDiffs().stream().anyMatch(d -> d.accountId() == accountId));
        } finally {
            jdbc.update("DELETE FROM wallet_ledger WHERE id = ?", ledgerId);
            jdbc.update("DELETE FROM wallet_account WHERE id = ?", accountId);
        }
    }
}
