package com.peergrab.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded, indexed cleanup independent of login traffic. */
@Component
public class AuthSessionCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(AuthSessionCleanupJob.class);
    private final JdbcTemplate jdbc;
    private final int batchSize;
    private final int maxBatches;

    public AuthSessionCleanupJob(JdbcTemplate jdbc,
                                 @Value("${peergrab.auth.cleanup-batch-size:500}") int batchSize,
                                 @Value("${peergrab.auth.cleanup-max-batches:20}") int maxBatches) {
        this.jdbc = jdbc;
        this.batchSize = Math.max(1, batchSize);
        this.maxBatches = Math.max(1, maxBatches);
    }

    @Scheduled(initialDelayString = "${peergrab.auth.cleanup-initial-delay-ms:60000}",
            fixedDelayString = "${peergrab.auth.cleanup-interval-ms:3600000}",
            scheduler = "maintenanceTaskScheduler")
    public void cleanup() {
        int refresh = deleteExpired("auth_refresh_token");
        int sessions = deleteExpired("auth_token_session");
        if (refresh + sessions > 0) {
            log.info("过期认证记录清理 refresh={} sessions={}", refresh, sessions);
        }
    }

    private int deleteExpired(String table) {
        int total = 0;
        for (int batch = 0; batch < maxBatches; batch++) {
            // Only fixed internal table names reach this method; no request input is interpolated.
            int removed = jdbc.update("DELETE FROM " + table
                    + " WHERE expires_at <= NOW(3) ORDER BY expires_at LIMIT ?", batchSize);
            total += removed;
            if (removed < batchSize) break;
        }
        return total;
    }
}
