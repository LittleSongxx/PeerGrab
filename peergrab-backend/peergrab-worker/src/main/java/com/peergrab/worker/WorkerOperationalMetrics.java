package com.peergrab.worker;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/** Cheap backlog gauges sampled outside Prometheus scrape requests. */
@Component
public class WorkerOperationalMetrics {

    private static final Logger log = LoggerFactory.getLogger(WorkerOperationalMetrics.class);

    private final JdbcTemplate jdbc;
    private final AtomicLong fundPending = new AtomicLong();
    private final AtomicLong fundOldestAgeSeconds = new AtomicLong();
    private final AtomicLong delayedPending = new AtomicLong();
    private final AtomicLong delayedDead = new AtomicLong();
    private final AtomicLong reconDiffsToday = new AtomicLong();

    public WorkerOperationalMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        registry.gauge("peergrab.fund.outbox.pending", fundPending);
        registry.gauge("peergrab.fund.outbox.oldest.age.seconds", fundOldestAgeSeconds);
        registry.gauge("peergrab.delay.outbox.pending", delayedPending);
        registry.gauge("peergrab.delay.outbox.dead", delayedDead);
        registry.gauge("peergrab.recon.diffs.today", reconDiffsToday);
    }

    @Scheduled(fixedDelay = 15000, scheduler = "maintenanceTaskScheduler")
    public void sample() {
        try {
            fundPending.set(count("SELECT COUNT(*) FROM fund_event_outbox WHERE status = 'PENDING'"));
            fundOldestAgeSeconds.set(count("""
                    SELECT COALESCE(TIMESTAMPDIFF(SECOND, MIN(created_at), NOW()), 0)
                      FROM fund_event_outbox WHERE status = 'PENDING'
                    """));
            delayedPending.set(count("SELECT COUNT(*) FROM local_message WHERE status = 'PENDING'"));
            delayedDead.set(count("SELECT COUNT(*) FROM local_message WHERE status = 'DEAD'"));
            reconDiffsToday.set(count("SELECT COUNT(*) FROM recon_diff WHERE check_date = CURRENT_DATE()"));
        } catch (RuntimeException e) {
            log.warn("后台积压指标采样失败", e);
        }
    }

    private long count(String sql) {
        Long result = jdbc.queryForObject(sql, Long.class);
        return result == null ? 0L : result;
    }
}
