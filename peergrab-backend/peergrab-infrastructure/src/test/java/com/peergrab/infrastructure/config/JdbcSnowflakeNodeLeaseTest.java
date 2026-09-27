package com.peergrab.infrastructure.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JdbcSnowflakeNodeLeaseTest {

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        var datasource = new DriverManagerDataSource(
                "jdbc:h2:mem:snowflake_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(datasource);
        jdbc.execute("""
                CREATE TABLE snowflake_node_lease (
                    node_id SMALLINT PRIMARY KEY, holder CHAR(36) NOT NULL,
                    expires_at TIMESTAMP NOT NULL, safe_after_ms BIGINT NOT NULL)
                """);
    }

    @Test
    void concurrentInstancesReceiveDifferentIdsAndReleasedIdWaitsForTimeFence() {
        var first = new JdbcSnowflakeNodeLease(jdbc, 1, 30);
        var second = new JdbcSnowflakeNodeLease(jdbc, 1, 30);
        long firstId = first.generator().nextId();
        long secondId = second.generator().nextId();
        assertEquals(1L, (firstId >> 12) & 1023);
        assertEquals(2L, (secondId >> 12) & 1023);

        first.close();
        var third = new JdbcSnowflakeNodeLease(jdbc, 1, 30);
        assertEquals(3L, (third.generator().nextId() >> 12) & 1023,
                "旧节点即使已释放，持久时间上界前也不能复用");
        assertThrows(IllegalStateException.class, () -> first.generator().nextId());
        second.close();
        third.close();
    }

    @Test
    void stolenLeaseRevokesGenerator() {
        var lease = new JdbcSnowflakeNodeLease(jdbc, 1, 30);
        var shutdowns = new java.util.concurrent.atomic.AtomicInteger();
        lease.onLeaseLost(shutdowns::incrementAndGet);
        jdbc.update("UPDATE snowflake_node_lease SET holder = 'other' WHERE node_id = 1");

        lease.renew();

        assertFalse(lease.canGenerateIds(), "失租必须反映到 readiness 依赖");
        assertEquals(1, shutdowns.get(), "仅触发一次受控退出");
        lease.renew();
        assertEquals(1, shutdowns.get());
        assertThrows(IllegalStateException.class, () -> lease.generator().nextId());
        lease.close();
    }

    @Test
    void expiredLeaseCanBeReusedAfterPreviousTimeFence() {
        var old = new JdbcSnowflakeNodeLease(jdbc, 1, 30);
        old.close();
        jdbc.update("UPDATE snowflake_node_lease SET safe_after_ms = ?, expires_at = NOW(3) WHERE node_id = 1",
                System.currentTimeMillis() - 1);

        var next = new JdbcSnowflakeNodeLease(jdbc, 1, 30);

        assertEquals(1L, (next.generator().nextId() >> 12) & 1023);
        next.close();
    }

    @Test
    void databaseFailureDuringRenewalRevokesGenerator() {
        var lease = new JdbcSnowflakeNodeLease(jdbc, 1, 30);
        jdbc.execute("DROP TABLE snowflake_node_lease");

        lease.renew();

        assertThrows(IllegalStateException.class, () -> lease.generator().nextId());
    }
}
