package com.peergrab.worker;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** MySQL 持久扫描失败项；唯一键去重，领取租约与有界退避防止重复风暴。 */
@Component
public class JdbcScanRetryQueue implements ScanRetryQueue {

    private static final int LEASE_SECONDS = 60;
    private static final int MAX_ATTEMPTS = 30;
    private static final int MAX_BACKOFF_SECONDS = 300;

    private final JdbcTemplate jdbc;

    public JdbcScanRetryQueue(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<Long, Integer> trackedRounds(Type type, List<Long> errandIds) {
        if (errandIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(errandIds.size(), "?"));
        List<Object> args = new ArrayList<>(errandIds.size() + 1);
        args.add(type.name());
        args.addAll(errandIds);
        Map<Long, Integer> tracked = new HashMap<>();
        jdbc.query("SELECT errand_id, round FROM worker_scan_retry WHERE job_type = ? AND errand_id IN ("
                        + placeholders + ")",
                (RowCallbackHandler) rs -> tracked.put(rs.getLong("errand_id"), rs.getInt("round")),
                args.toArray());
        return tracked;
    }

    @Override
    public void recordFailure(Type type, long errandId, int round, Exception failure) {
        jdbc.update("""
                INSERT INTO worker_scan_retry
                    (job_type, errand_id, round, attempts, next_retry_at, last_error)
                VALUES (?, ?, ?, 1, DATE_ADD(NOW(3), INTERVAL 5 SECOND), ?)
                ON DUPLICATE KEY UPDATE
                    attempts = IF(round = VALUES(round), attempts, 1),
                    next_retry_at = IF(round = VALUES(round), next_retry_at, VALUES(next_retry_at)),
                    lease_token = IF(round = VALUES(round), lease_token, NULL),
                    lease_until = IF(round = VALUES(round), lease_until, NULL),
                    round = VALUES(round),
                    last_error = VALUES(last_error)
                """, type.name(), errandId, round, errorText(failure));
    }

    @Override
    public List<RetryItem> claimDue(Type type, int limit) {
        List<RetryItem> due = jdbc.query("""
                SELECT errand_id, round, attempts
                  FROM worker_scan_retry
                 WHERE job_type = ? AND next_retry_at <= NOW(3)
                   AND (lease_until IS NULL OR lease_until <= NOW(3))
                 ORDER BY next_retry_at, errand_id
                 LIMIT ?
                """, (rs, row) -> new RetryItem(rs.getLong("errand_id"), rs.getInt("round"),
                rs.getInt("attempts"), null), type.name(), limit);
        List<RetryItem> claimed = new ArrayList<>(due.size());
        for (RetryItem item : due) {
            String token = UUID.randomUUID().toString();
            int updated = jdbc.update("""
                    UPDATE worker_scan_retry
                       SET lease_token = ?, lease_until = DATE_ADD(NOW(3), INTERVAL ? SECOND)
                     WHERE job_type = ? AND errand_id = ? AND round = ?
                       AND next_retry_at <= NOW(3)
                       AND (lease_until IS NULL OR lease_until <= NOW(3))
                    """, token, LEASE_SECONDS, type.name(), item.errandId(), item.round());
            if (updated == 1) {
                claimed.add(new RetryItem(item.errandId(), item.round(), item.attempts(), token));
            }
        }
        return claimed;
    }

    @Override
    public void complete(Type type, RetryItem item) {
        jdbc.update("DELETE FROM worker_scan_retry WHERE job_type = ? AND errand_id = ? AND round = ? AND lease_token = ?",
                type.name(), item.errandId(), item.round(), item.leaseToken());
    }

    @Override
    public void reschedule(Type type, RetryItem item, Exception failure) {
        int attempts = Math.min(MAX_ATTEMPTS, item.attempts() + 1);
        int backoff = backoffSeconds(attempts);
        jdbc.update("""
                UPDATE worker_scan_retry
                   SET attempts = ?, next_retry_at = DATE_ADD(NOW(3), INTERVAL ? SECOND),
                       lease_token = NULL, lease_until = NULL, last_error = ?
                 WHERE job_type = ? AND errand_id = ? AND round = ? AND lease_token = ?
                """, attempts, backoff, errorText(failure), type.name(), item.errandId(),
                item.round(), item.leaseToken());
    }

    private static String errorText(Exception failure) {
        String message = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        return message.length() <= 255 ? message : message.substring(0, 255);
    }

    static int backoffSeconds(int attempts) {
        return Math.min(MAX_BACKOFF_SECONDS, 5 << Math.min(Math.max(attempts - 1, 0), 6));
    }
}
