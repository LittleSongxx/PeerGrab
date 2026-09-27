package com.peergrab.infrastructure.persistence;

import com.peergrab.domain.errand.ports.LocalMessageRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

@Repository
public class JdbcLocalMessageRepository implements LocalMessageRepository {

    private final JdbcTemplate jdbc;

    public JdbcLocalMessageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记待发消息。撞 uk_msg_key 说明这一轮已经登记过，返回 false 而不是抛异常——
     * 重复登记是预期情况（消息重投、请求重试），不该让业务事务回滚。
     */
    @Override
    public boolean enqueue(long id, String msgKey, String topic, String payload, Instant deliverAt) {
        try {
            jdbc.update("""
                    INSERT INTO local_message (id, msg_key, topic, payload, deliver_at, status, next_retry_at)
                    VALUES (?, ?, ?, ?, ?, 'PENDING', NOW(3))
                    """, id, msgKey, topic, payload,
                    Timestamp.from(deliverAt));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public void markSent(String msgKey) {
        jdbc.update("""
                UPDATE local_message SET status = 'SENT', claim_token = NULL, claim_until = NULL
                 WHERE msg_key = ? AND status = 'PENDING'
                """, msgKey);
    }

    @Override
    public List<PendingMessage> findPending(int limit) {
        return jdbc.query("""
                SELECT id, msg_key, topic, payload, deliver_at, retry_count
                  FROM local_message
                 WHERE status = 'PENDING' AND next_retry_at <= NOW(3)
                 ORDER BY next_retry_at
                 LIMIT ?
                """, (rs, n) -> new PendingMessage(
                        rs.getLong("id"), rs.getString("msg_key"), rs.getString("topic"),
                        rs.getString("payload"), rs.getTimestamp("deliver_at").toInstant(),
                        rs.getInt("retry_count")), limit);
    }

    @Override
    public List<ClaimedMessage> claimPending(int limit) {
        List<PendingMessage> candidates = jdbc.query("""
                SELECT id, msg_key, topic, payload, deliver_at, retry_count
                  FROM local_message
                 WHERE status = 'PENDING' AND next_retry_at <= NOW(3)
                   AND (claim_until IS NULL OR claim_until < NOW(3))
                 ORDER BY next_retry_at, id
                 LIMIT ?
                """, (rs, n) -> new PendingMessage(
                rs.getLong("id"), rs.getString("msg_key"), rs.getString("topic"),
                rs.getString("payload"), rs.getTimestamp("deliver_at").toInstant(),
                rs.getInt("retry_count")), limit);
        List<ClaimedMessage> claimed = new ArrayList<>(candidates.size());
        for (PendingMessage message : candidates) {
            String token = UUID.randomUUID().toString();
            int rows = jdbc.update("""
                    UPDATE local_message
                       SET claim_token = ?, claim_until = TIMESTAMPADD(SECOND, 120, NOW(3))
                     WHERE id = ? AND status = 'PENDING' AND next_retry_at <= NOW(3)
                       AND (claim_until IS NULL OR claim_until < NOW(3))
                    """, token, message.id());
            if (rows == 1) claimed.add(new ClaimedMessage(message, token));
        }
        return claimed;
    }

    @Override
    public boolean markClaimedSent(String msgKey, String claimToken) {
        return jdbc.update("""
                UPDATE local_message SET status = 'SENT', claim_token = NULL, claim_until = NULL
                 WHERE msg_key = ? AND status = 'PENDING' AND claim_token = ?
                """, msgKey, claimToken) == 1;
    }

    @Override
    public boolean markClaimedRetry(String msgKey, String claimToken, int maxRetry) {
        return jdbc.update("""
                UPDATE local_message
                   SET retry_count = retry_count + 1,
                       status = CASE WHEN retry_count + 1 >= ? THEN 'DEAD' ELSE 'PENDING' END,
                       next_retry_at = TIMESTAMPADD(SECOND,
                           LEAST(POWER(2, LEAST(retry_count + 1, 9)), 300), NOW(3)),
                       claim_token = NULL, claim_until = NULL
                 WHERE msg_key = ? AND status = 'PENDING' AND claim_token = ?
                """, maxRetry, msgKey, claimToken) == 1;
    }

    /**
     * 重试失败处理：指数退避（2^retry 秒，上限 5 分钟），超过上限转 DEAD 人工介入。
     * 不无限重试是为了避免一条坏消息把 worker 拖死。
     */
    @Override
    public void markRetry(String msgKey, int maxRetry) {
        jdbc.update("""
                UPDATE local_message
                   SET retry_count = retry_count + 1,
                       status = CASE WHEN retry_count + 1 >= ? THEN 'DEAD' ELSE 'PENDING' END,
                       next_retry_at = TIMESTAMPADD(SECOND,
                           LEAST(POWER(2, LEAST(retry_count + 1, 9)), 300), NOW(3))
                 WHERE msg_key = ? AND status = 'PENDING' AND claim_token IS NULL
                """, maxRetry, msgKey);
    }
}
