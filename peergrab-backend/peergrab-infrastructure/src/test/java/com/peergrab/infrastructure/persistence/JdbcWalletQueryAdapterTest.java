package com.peergrab.infrastructure.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdbcWalletQueryAdapterTest {

    @Test
    void cursor_keeps_order_across_equal_timestamps_and_stays_within_user() {
        var datasource = new DriverManagerDataSource(
                "jdbc:h2:mem:ledger_cursor_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(datasource);
        jdbc.execute("""
                CREATE TABLE wallet_ledger (
                  id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, created_at TIMESTAMP(3) NOT NULL,
                  direction VARCHAR(8), amount BIGINT, ref_type VARCHAR(24), ref_id BIGINT,
                  biz_no VARCHAR(64))
                """);
        jdbc.execute("CREATE INDEX idx_ledger_user_time ON wallet_ledger(user_id, created_at, id)");
        Instant time = Instant.parse("2026-09-27T02:03:04.123Z");
        insert(jdbc, 9_223_372_036_854_775_000L, 1001, time);
        insert(jdbc, 9_223_372_036_854_774_999L, 1001, time);
        insert(jdbc, 9_223_372_036_854_774_998L, 1001, time);
        insert(jdbc, 9_223_372_036_854_775_001L, 2002, time);

        var adapter = new JdbcWalletQueryAdapter(jdbc);
        var first = adapter.ledgerByCursor(1001, null, null, 2);
        assertEquals(2, first.size());
        assertEquals(9_223_372_036_854_775_000L, first.get(0).id());
        assertEquals(9_223_372_036_854_774_999L, first.get(1).id());

        var second = adapter.ledgerByCursor(1001, first.get(1).time(), first.get(1).id(), 2);
        assertEquals(1, second.size());
        assertEquals(9_223_372_036_854_774_998L, second.get(0).id());
    }

    private static void insert(JdbcTemplate jdbc, long id, long userId, Instant time) {
        jdbc.update("""
                INSERT INTO wallet_ledger
                    (id, user_id, created_at, direction, amount, ref_type, ref_id, biz_no)
                VALUES (?, ?, ?, 'CREDIT', 100, 'SETTLE', ?, ?)
                """, id, userId, Timestamp.from(time), id, "settle:" + id);
    }
}
