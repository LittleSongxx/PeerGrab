package com.peergrab.shared;

import java.util.function.LongSupplier;

/**
 * 雪花 ID：趋势递增且全局唯一。
 * 不用自增 ID 是因为分库分表后会冲突；不用 UUID 是因为无序会导致 InnoDB 页分裂、写入变慢。
 * workerId 在一期由配置指定，P6 分库分表时改为启动时从 Redis 分配，避免人工配错导致 ID 重复。
 */
public class SnowflakeIdGenerator {

    private static final long EPOCH = 1735689600000L; // 2025-01-01 00:00:00 UTC
    private static final long WORKER_ID_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;
    public static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;

    /** 允许的时钟回拨窗口：NTP 小幅回拨时等待越过，超过则拒绝发号。 */
    private final long maxBackwardMillis;
    private final LongSupplier clock;
    private final long workerId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;
    private boolean revoked;
    private long leaseDeadlineNanos = Long.MAX_VALUE;
    private long timestampCeilingMillis = Long.MAX_VALUE;

    public SnowflakeIdGenerator(long workerId) {
        this(workerId, 100L, System::currentTimeMillis);
    }

    /** 测试可注入时钟；maxBackwardMillis 内的小幅回拨会等待时间追上而不是直接失败。 */
    public SnowflakeIdGenerator(long workerId, long maxBackwardMillis, LongSupplier clock) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId 必须在 0.." + MAX_WORKER_ID + " 之间");
        }
        this.workerId = workerId;
        this.maxBackwardMillis = Math.max(0, maxBackwardMillis);
        this.clock = clock;
    }

    /** synchronized 足够：单机 ID 生成不是瓶颈，4096/ms 的容量远超单机写入能力 */
    public synchronized long nextId() {
        if (revoked || (leaseDeadlineNanos != Long.MAX_VALUE
                && System.nanoTime() - leaseDeadlineNanos >= 0)) {
            revoked = true;
            throw new IllegalStateException("雪花节点租约已失效，拒绝生成 ID");
        }
        long timestamp = clock.getAsLong();
        if (timestamp >= timestampCeilingMillis) {
            revoked = true;
            throw new IllegalStateException("雪花节点时间已达到持久租约上界，拒绝生成 ID");
        }
        if (timestamp < lastTimestamp) {
            long drift = lastTimestamp - timestamp;
            if (drift > maxBackwardMillis) {
                throw new IllegalStateException("时钟回拨超过容忍窗口，拒绝生成 ID，差值(ms)=" + drift);
            }
            // 小幅回拨：等待到上一毫秒之后，避免同一毫秒序列号回绕导致重复 ID。
            timestamp = waitNextMillis(lastTimestamp);
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                timestamp = waitNextMillis(lastTimestamp);
                if (timestamp >= timestampCeilingMillis) {
                    revoked = true;
                    throw new IllegalStateException("雪花节点时间已达到持久租约上界，拒绝生成 ID");
                }
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT) | (workerId << WORKER_ID_SHIFT) | sequence;
    }

    /** 租约只可续长；被撤销后不能在同一进程内重新启用。 */
    public synchronized void extendLease(long deadlineNanos, long ceilingMillis) {
        if (revoked) throw new IllegalStateException("雪花节点租约已撤销");
        leaseDeadlineNanos = deadlineNanos;
        timestampCeilingMillis = Math.max(timestampCeilingMillis == Long.MAX_VALUE ? 0 : timestampCeilingMillis,
                ceilingMillis);
    }

    public synchronized void revoke() {
        revoked = true;
    }

    /** Readiness checks the same local fences as nextId without issuing an ID. */
    public synchronized boolean canGenerate() {
        return !revoked
                && (leaseDeadlineNanos == Long.MAX_VALUE
                    || System.nanoTime() - leaseDeadlineNanos < 0)
                && clock.getAsLong() < timestampCeilingMillis
                && clock.getAsLong() >= lastTimestamp;
    }

    private long waitNextMillis(long lastTimestamp) {
        long timestamp = clock.getAsLong();
        while (timestamp <= lastTimestamp) {
            if (leaseDeadlineNanos != Long.MAX_VALUE && System.nanoTime() - leaseDeadlineNanos >= 0) {
                revoked = true;
                throw new IllegalStateException("雪花节点租约已失效，拒绝生成 ID");
            }
            timestamp = clock.getAsLong();
        }
        return timestamp;
    }
}
