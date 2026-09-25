package com.peergrab.infrastructure.persistence;

import com.peergrab.domain.grab.model.GrabRecord;
import com.peergrab.domain.grab.ports.GrabRecordRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JdbcGrabRecordRepository implements GrabRecordRepository {

    private final JdbcTemplate jdbc;

    public JdbcGrabRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 注意这里用的是普通 INSERT，不是 INSERT IGNORE。
     * 撞唯一索引必须抛 DuplicateKeyException 让上层感知，
     * 因为这两个唯一索引就是防超卖的最后一道防线，把冲突吞掉等于把防线拆了。
     */
    @Override
    public void insert(GrabRecord record) {
        jdbc.update("""
                INSERT INTO grab_record (id, campus_id, errand_id, runner_id, seq, round, result, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                record.id(), record.campusId(), record.errandId(), record.runnerId(),
                record.seq(), record.round(), record.result().name(), record.requestId());
    }

    @Override
    public int countGrabbed(long campusId, long errandId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM grab_record WHERE campus_id = ? AND errand_id = ? AND result = 'GRABBED'",
                Integer.class, campusId, errandId);
        return n == null ? 0 : n;
    }

    @Override
    public Optional<Long> findRunnerByRequestId(long campusId, long errandId, String requestId) {
        List<Long> rows = jdbc.query("""
                SELECT runner_id FROM grab_record
                 WHERE campus_id = ? AND errand_id = ? AND request_id = ? AND result = 'GRABBED'
                """, (rs, rowNum) -> rs.getLong(1), campusId, errandId, requestId);
        return rows.stream().findFirst();
    }
}
