package com.peergrab.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

/** Worker 读取持久资金事件；发送成功后才改为 SENT。 */
@Repository
public class JdbcFundEventOutboxRepository {

    private final JdbcTemplate jdbc;

    public JdbcFundEventOutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record PendingEvent(String bizNo, String type, long errandId, long publisherId,
                               long runnerId, long amountCents, long commissionCents, int retryCount) {}
    public record ClaimedEvent(PendingEvent event, String claimToken) {}

    public List<PendingEvent> findPending(int limit) {
        return jdbc.query("""
                SELECT biz_no, event_type, errand_id, publisher_id, runner_id,
                       amount_cents, commission_cents, retry_count
                  FROM fund_event_outbox
                 WHERE status = 'PENDING' AND next_retry_at <= NOW(3)
                 ORDER BY next_retry_at, biz_no
                 LIMIT ?
                """, (rs, row) -> new PendingEvent(
                rs.getString("biz_no"), rs.getString("event_type"), rs.getLong("errand_id"),
                rs.getLong("publisher_id"), rs.getLong("runner_id"), rs.getLong("amount_cents"),
                rs.getLong("commission_cents"), rs.getInt("retry_count")), limit);
    }

    public List<ClaimedEvent> claimPending(int limit) {
        List<PendingEvent> candidates = jdbc.query("""
                SELECT biz_no, event_type, errand_id, publisher_id, runner_id,
                       amount_cents, commission_cents, retry_count
                  FROM fund_event_outbox
                 WHERE status = 'PENDING' AND next_retry_at <= NOW(3)
                   AND (claim_until IS NULL OR claim_until < NOW(3))
                 ORDER BY next_retry_at, biz_no LIMIT ?
                """, (rs, row) -> new PendingEvent(
                rs.getString("biz_no"), rs.getString("event_type"), rs.getLong("errand_id"),
                rs.getLong("publisher_id"), rs.getLong("runner_id"), rs.getLong("amount_cents"),
                rs.getLong("commission_cents"), rs.getInt("retry_count")), limit);
        List<ClaimedEvent> claimed = new ArrayList<>(candidates.size());
        for (PendingEvent event : candidates) {
            String token = UUID.randomUUID().toString();
            int rows = jdbc.update("""
                    UPDATE fund_event_outbox
                       SET claim_token = ?, claim_until = TIMESTAMPADD(SECOND, 120, NOW(3))
                     WHERE biz_no = ? AND status = 'PENDING' AND next_retry_at <= NOW(3)
                       AND (claim_until IS NULL OR claim_until < NOW(3))
                    """, token, event.bizNo());
            if (rows == 1) claimed.add(new ClaimedEvent(event, token));
        }
        return claimed;
    }

    public boolean markClaimedSent(String bizNo, String claimToken) {
        return jdbc.update("""
                UPDATE fund_event_outbox SET status = 'SENT', claim_token = NULL, claim_until = NULL
                 WHERE biz_no = ? AND status = 'PENDING' AND claim_token = ?
                """, bizNo, claimToken) == 1;
    }

    public boolean markClaimedRetry(String bizNo, String claimToken) {
        return jdbc.update("""
                UPDATE fund_event_outbox
                   SET retry_count = LEAST(retry_count + 1, 30),
                       next_retry_at = TIMESTAMPADD(SECOND,
                           LEAST(POWER(2, LEAST(retry_count + 1, 9)), 300), NOW(3)),
                       claim_token = NULL, claim_until = NULL
                 WHERE biz_no = ? AND status = 'PENDING' AND claim_token = ?
                """, bizNo, claimToken) == 1;
    }

    public void markSent(String bizNo) {
        jdbc.update("UPDATE fund_event_outbox SET status = 'SENT' WHERE biz_no = ? AND status = 'PENDING'", bizNo);
    }

    public void markRetry(String bizNo) {
        // 数据来自本地事务，重试不自动转 DEAD：长时间 MQ 故障恢复后仍须投递。
        jdbc.update("""
                UPDATE fund_event_outbox
                   SET retry_count = LEAST(retry_count + 1, 30),
                       next_retry_at = TIMESTAMPADD(SECOND,
                           LEAST(POWER(2, LEAST(retry_count + 1, 9)), 300), NOW(3))
                 WHERE biz_no = ? AND status = 'PENDING' AND claim_token IS NULL
                """, bizNo);
    }
}
