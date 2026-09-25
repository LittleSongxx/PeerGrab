package com.peergrab.infrastructure.persistence;

import com.peergrab.domain.errand.ports.PublishRequestRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class JdbcPublishRequestRepository implements PublishRequestRepository {

    private final JdbcTemplate jdbc;

    public JdbcPublishRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Claim claimAndLock(long publisherId, String requestId, String payloadHash) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("publish claim requires an active transaction");
        }
        // The unique (publisher_id, request_id) index serializes concurrent submissions.
        // A competing INSERT waits for the first transaction to commit or roll back.
        jdbc.update("""
                INSERT INTO publish_request (publisher_id, request_id, payload_hash)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE publisher_id = publisher_id
                """, publisherId, requestId, payloadHash);
        return jdbc.queryForObject("""
                SELECT payload_hash, errand_id
                  FROM publish_request
                 WHERE publisher_id = ? AND request_id = ?
                FOR UPDATE
                """, (rs, rowNum) -> {
            String storedHash = rs.getString("payload_hash");
            long errandId = rs.getLong("errand_id");
            boolean missingErrandId = rs.wasNull();
            return new Claim(storedHash, missingErrandId ? null : errandId);
        }, publisherId, requestId);
    }

    @Override
    public int complete(long publisherId, String requestId, long errandId) {
        return jdbc.update("""
                UPDATE publish_request SET errand_id = ?
                 WHERE publisher_id = ? AND request_id = ? AND errand_id IS NULL
                """, errandId, publisherId, requestId);
    }
}
