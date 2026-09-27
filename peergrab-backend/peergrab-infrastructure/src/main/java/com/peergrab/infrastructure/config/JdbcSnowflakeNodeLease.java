package com.peergrab.infrastructure.config;

import com.peergrab.shared.SnowflakeIdGenerator;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * MySQL 裁决节点 ID 所有权。数据库租约 30 秒、本地仅允许发号约 20 秒；
 * 下一个持有者还必须越过上一持有者持久化的时间上界，才能复用同一 ID。
 */
public final class JdbcSnowflakeNodeLease {

    private static final Logger log = LoggerFactory.getLogger(JdbcSnowflakeNodeLease.class);
    private final JdbcTemplate jdbc;
    private final String holder = UUID.randomUUID().toString();
    private final int leaseSeconds;
    private final int nodeId;
    private final SnowflakeIdGenerator generator;
    private Runnable lossHandler = () -> {};
    private boolean closed;

    public JdbcSnowflakeNodeLease(JdbcTemplate jdbc, int preferredNodeId, int leaseSeconds) {
        if (preferredNodeId < 0 || preferredNodeId > SnowflakeIdGenerator.MAX_WORKER_ID) {
            throw new IllegalArgumentException("首选 workerId 必须在 0..1023 之间");
        }
        if (leaseSeconds < 15 || leaseSeconds > 300) {
            throw new IllegalArgumentException("雪花节点租约必须在 15..300 秒之间");
        }
        this.jdbc = jdbc;
        this.leaseSeconds = leaseSeconds;
        Acquired acquired = acquire(preferredNodeId);
        this.nodeId = acquired.nodeId();
        this.generator = new SnowflakeIdGenerator(nodeId);
        generator.extendLease(acquired.localDeadlineNanos(), acquired.ceilingMillis());
        log.info("雪花节点租约已取得 nodeId={} holder={}", nodeId, holder);
    }

    public SnowflakeIdGenerator generator() {
        return generator;
    }

    public synchronized boolean canGenerateIds() {
        return !closed && generator.canGenerate();
    }

    public synchronized void onLeaseLost(Runnable handler) {
        this.lossHandler = java.util.Objects.requireNonNull(handler);
    }

    private record Acquired(int nodeId, long localDeadlineNanos, long ceilingMillis) {}

    private Acquired acquire(int preferredNodeId) {
        int count = (int) SnowflakeIdGenerator.MAX_WORKER_ID + 1;
        for (int offset = 0; offset < count; offset++) {
            int candidate = (preferredNodeId + offset) % count;
            long startedNanos = System.nanoTime();
            long nowMillis = System.currentTimeMillis();
            long ceilingMillis = Math.addExact(nowMillis, 2_000L * leaseSeconds);
            long deadlineNanos = localDeadline(startedNanos);

            int inserted = jdbc.update("""
                    INSERT IGNORE INTO snowflake_node_lease (node_id, holder, expires_at, safe_after_ms)
                    VALUES (?, ?, TIMESTAMPADD(SECOND, ?, NOW(3)), ?)
                    """, candidate, holder, leaseSeconds, ceilingMillis);
            if (inserted == 1) {
                return new Acquired(candidate, deadlineNanos, ceilingMillis);
            }

            // 先看上一持有者的持久上界；时钟未越过时使用别的 ID，不能重发旧时间段的序号。
            Long oldCeiling = jdbc.query("SELECT safe_after_ms FROM snowflake_node_lease WHERE node_id = ?",
                    rs -> rs.next() ? rs.getLong(1) : null, candidate);
            if (oldCeiling == null || nowMillis <= oldCeiling) continue;
            int claimed = jdbc.update("""
                    UPDATE snowflake_node_lease
                       SET holder = ?, expires_at = TIMESTAMPADD(SECOND, ?, NOW(3)),
                           safe_after_ms = ?
                     WHERE node_id = ? AND expires_at <= NOW(3) AND safe_after_ms < ?
                    """, holder, leaseSeconds, ceilingMillis, candidate, nowMillis);
            if (claimed == 1) {
                return new Acquired(candidate, deadlineNanos, ceilingMillis);
            }
        }
        throw new IllegalStateException("没有可安全分配的雪花节点 ID；检查租约与主机时钟");
    }

    private long localDeadline(long startedNanos) {
        return startedNanos + TimeUnit.SECONDS.toNanos(leaseSeconds * 2L / 3L);
    }

    @Scheduled(fixedDelayString = "${peergrab.worker-lease.renew-interval-ms:5000}",
            scheduler = "snowflakeLeaseScheduler")
    public synchronized void renew() {
        if (closed) return;
        long startedNanos = System.nanoTime();
        long ceilingMillis = Math.addExact(System.currentTimeMillis(), 2_000L * leaseSeconds);
        try {
            int updated = jdbc.update("""
                    UPDATE snowflake_node_lease
                       SET expires_at = TIMESTAMPADD(SECOND, ?, NOW(3)),
                           safe_after_ms = GREATEST(safe_after_ms, ?)
                     WHERE node_id = ? AND holder = ? AND expires_at > NOW(3)
                    """, leaseSeconds, ceilingMillis, nodeId, holder);
            if (updated != 1) {
                throw new IllegalStateException("雪花节点租约已被接管或过期 nodeId=" + nodeId);
            }
            generator.extendLease(localDeadline(startedNanos), ceilingMillis);
        } catch (RuntimeException e) {
            closed = true;
            generator.revoke();
            log.error("雪花节点续租失败，进程永久停止发号 nodeId={}", nodeId, e);
            lossHandler.run();
        }
    }

    @PreDestroy
    public synchronized void close() {
        generator.revoke();
        closed = true;
        try {
            // 只提前到期，不删除时间栅栏；新持有者仍须越过 safe_after_ms。
            jdbc.update("UPDATE snowflake_node_lease SET expires_at = NOW(3) "
                    + "WHERE node_id = ? AND holder = ?", nodeId, holder);
        } catch (RuntimeException e) {
            log.warn("雪花节点租约释放失败，等待自然过期 nodeId={}", nodeId, e);
        }
    }
}
