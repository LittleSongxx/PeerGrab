package com.peergrab.infrastructure.persistence;

import com.peergrab.domain.wallet.ports.FundEventPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JdbcFundEventOutboxTransactionTest {

    private JdbcTemplate jdbc;
    private JdbcFundEventOutboxAdapter adapter;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        var datasource = new DriverManagerDataSource(
                "jdbc:h2:mem:fund_outbox_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(datasource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(datasource));
        adapter = new JdbcFundEventOutboxAdapter(jdbc);
        jdbc.execute("CREATE TABLE wallet_probe (id BIGINT PRIMARY KEY, balance BIGINT NOT NULL)");
        jdbc.execute("INSERT INTO wallet_probe (id, balance) VALUES (1, 100)");
        jdbc.execute("""
                CREATE TABLE fund_event_outbox (
                    biz_no VARCHAR(64) PRIMARY KEY, event_type VARCHAR(32), errand_id BIGINT,
                    publisher_id BIGINT, runner_id BIGINT, amount_cents BIGINT,
                    commission_cents BIGINT, status VARCHAR(16), next_retry_at TIMESTAMP,
                    retry_count INT DEFAULT 0)
                """);
    }

    @Test
    void fundWorkAndOutboxCommitInOneCallerTransaction() {
        assertTrue(Boolean.TRUE.equals(transaction.execute(status -> {
            jdbc.update("UPDATE wallet_probe SET balance = balance - 20 WHERE id = 1");
            adapter.append(event("settle:41"));
            return true;
        })));

        assertEquals(80L, jdbc.queryForObject("SELECT balance FROM wallet_probe WHERE id = 1", Long.class));
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM fund_event_outbox WHERE biz_no = 'settle:41'", String.class));
    }

    @Test
    void failedOutboxInsertRollsBackFundWork() {
        transaction.execute(status -> {
            adapter.append(event("settle:41"));
            return true;
        });

        assertThrows(DuplicateKeyException.class, () -> transaction.execute(status -> {
            jdbc.update("UPDATE wallet_probe SET balance = balance - 20 WHERE id = 1");
            adapter.append(event("settle:41"));
            return true;
        }));

        assertEquals(100L, jdbc.queryForObject("SELECT balance FROM wallet_probe WHERE id = 1", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM fund_event_outbox", Long.class));
    }

    @Test
    void rejectedFundWorkLeavesNoEvent() {
        assertFalse(Boolean.TRUE.equals(transaction.execute(status -> {
            jdbc.update("UPDATE wallet_probe SET balance = balance - 20 WHERE id = 1");
            status.setRollbackOnly();
            return false;
        })));

        assertEquals(100L, jdbc.queryForObject("SELECT balance FROM wallet_probe WHERE id = 1", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM fund_event_outbox", Long.class));
    }

    private static FundEventPort.FundEvent event(String bizNo) {
        return new FundEventPort.FundEvent(bizNo, "SETTLED", 41, 1001, 2001, 950, 50);
    }
}
