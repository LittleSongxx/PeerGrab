package com.peergrab.infrastructure.persistence;

import com.peergrab.domain.errand.model.Errand;
import com.peergrab.domain.errand.ports.ErrandQueryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public class JdbcErrandQueryAdapter implements ErrandQueryPort {

    /** 读模型的字段映射与写仓储共用一套 rehydrate 逻辑，避免两边漂移 */
    private static final RowMapper<Errand> MAPPER = (rs, n) -> Errand.rehydrate(
            rs.getLong("id"), rs.getLong("campus_id"), rs.getLong("publisher_id"),
            com.peergrab.domain.errand.model.ErrandType.valueOf(rs.getString("type")),
            rs.getString("title"),
            com.peergrab.shared.Money.ofCents(rs.getLong("reward_amount")),
            rs.getInt("slot_total"),
            rs.getObject("grabber_id") == null ? null : rs.getLong("grabber_id"),
            com.peergrab.domain.errand.model.ErrandStatus.valueOf(rs.getString("status")),
            rs.getInt("slot_taken"), rs.getInt("round"), rs.getLong("version"),
            rs.getTimestamp("locked_at") == null ? null : rs.getTimestamp("locked_at").toInstant(),
            rs.getTimestamp("delivered_at") == null ? null : rs.getTimestamp("delivered_at").toInstant(),
            null, null, rs.getInt("transfer_count"));

    private final JdbcTemplate jdbc;

    public JdbcErrandQueryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Errand> list(long campusId, String status, int page, int size) {
        return jdbc.query("""
                SELECT * FROM errand
                 WHERE campus_id = ? AND status = ?
                 ORDER BY created_at DESC
                 LIMIT ? OFFSET ?
                """, MAPPER, campusId, status, size, (long) page * size);
    }

    @Override
    public List<CursorItem> listByCursor(long campusId, String status, Instant beforeCreatedAt, Long beforeId, int size) {
        if (beforeCreatedAt == null || beforeId == null) {
            return jdbc.query("""
                    SELECT * FROM errand
                     WHERE campus_id = ? AND status = ?
                     ORDER BY created_at DESC, id DESC
                     LIMIT ?
                    """, (rs, n) -> new CursorItem(MAPPER.mapRow(rs, n),
                            rs.getTimestamp("created_at").toInstant()),
                    campusId, status, size);
        }
        return jdbc.query("""
                SELECT * FROM errand
                 WHERE campus_id = ? AND status = ?
                   AND (created_at < ? OR (created_at = ? AND id < ?))
                 ORDER BY created_at DESC, id DESC
                 LIMIT ?
                """, (rs, n) -> new CursorItem(MAPPER.mapRow(rs, n),
                        rs.getTimestamp("created_at").toInstant()),
                campusId, status,
                java.sql.Timestamp.from(beforeCreatedAt), java.sql.Timestamp.from(beforeCreatedAt),
                beforeId, size);
    }

    @Override
    public List<Errand> listByPublisher(long publisherId, int page, int size) {
        return jdbc.query("""
                SELECT * FROM errand
                 WHERE publisher_id = ?
                 ORDER BY created_at DESC
                 LIMIT ? OFFSET ?
                """, MAPPER, publisherId, size, (long) page * size);
    }

    @Override
    public List<Errand> listByRunner(long runnerId, int page, int size) {
        // "我抢到的任务"按当前 grabber 归属查询：任务被递补/回退后不会错留在前一位跑腿名下。
        return jdbc.query("""
                SELECT e.* FROM errand e
                 WHERE e.grabber_id = ?
                 ORDER BY e.created_at DESC
                 LIMIT ? OFFSET ?
                """, MAPPER, runnerId, size, (long) page * size);
    }

    @Override
    public List<StatusChange> statusLog(long campusId, long errandId) {
        return jdbc.query("""
                SELECT created_at, from_status, to_status, round, operator_id
                  FROM errand_status_log
                 WHERE campus_id = ? AND errand_id = ?
                 ORDER BY created_at, id
                """, (rs, n) -> new StatusChange(
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getString("from_status"), rs.getString("to_status"),
                        rs.getInt("round"), rs.getLong("operator_id")), campusId, errandId);
    }

    /**
     * 随机抽样：ORDER BY RAND() 会对全表排序；改为"在 min/max 之间随机起点 + 主键范围扫描"，
     * 总量大时仍能保持近似均匀且成本可控（雪花 ID 有空洞不影响抽样目的）。
     */
    @Override
    public List<Long> sampleIds(int limit) {
        Long min = jdbc.queryForObject("SELECT MIN(id) FROM errand", Long.class);
        Long max = jdbc.queryForObject("SELECT MAX(id) FROM errand", Long.class);
        if (min == null || max == null) return List.of();
        long span = max - min;
        long start = span <= 0 ? min
                : min + java.util.concurrent.ThreadLocalRandom.current().nextLong(span + 1);
        List<Long> ids = new java.util.ArrayList<>(jdbc.queryForList(
                "SELECT id FROM errand WHERE id >= ? ORDER BY id LIMIT ?", Long.class, start, limit));
        if (ids.size() < limit) {
            ids.addAll(jdbc.queryForList(
                    "SELECT id FROM errand WHERE id < ? ORDER BY id LIMIT ?", Long.class,
                    start, limit - ids.size()));
        }
        return ids;
    }

    @Override
    public List<Long> scanIdsAfter(long afterId, int limit) {
        return jdbc.queryForList("SELECT id FROM errand WHERE id > ? ORDER BY id LIMIT ?",
                Long.class, afterId, limit);
    }

    @Override
    public int countOngoingByRunner(long runnerId) {
        // 抢中即占用额度，送达后释放；与 JdbcRunnerQuotaAdapter 的事务内裁决保持一致。
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM errand
                 WHERE grabber_id = ? AND status IN ('LOCKED', 'ACCEPTED', 'PICKED_UP')
                """, Integer.class, runnerId);
        return n == null ? 0 : n;
    }
}
