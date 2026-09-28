package com.peergrab.infrastructure.persistence;

import com.peergrab.domain.credit.model.CreditEvent;
import com.peergrab.domain.credit.model.CreditEventType;
import com.peergrab.domain.credit.model.CreditScore;
import com.peergrab.domain.credit.ports.CreditRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Repository
public class JdbcCreditRepository implements CreditRepository {

    private final JdbcTemplate jdbc;
    private final Timer scoreAcquireTimer;
    private final Timer applyReplayTimer;

    public JdbcCreditRepository(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.scoreAcquireTimer = Timer.builder("peergrab.credit.score.acquire")
                .description("Time to create or lock the credit_score row before applying an event")
                .serviceLevelObjectives(Duration.ofMillis(1), Duration.ofMillis(5),
                        Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50),
                        Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500),
                        Duration.ofSeconds(1))
                .register(registry);
        this.applyReplayTimer = Timer.builder("peergrab.credit.apply.replay")
                .description("Time to replay recent credit events inside applyEvent")
                .serviceLevelObjectives(Duration.ofMillis(1), Duration.ofMillis(5),
                        Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50),
                        Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500),
                        Duration.ofSeconds(1))
                .register(registry);
    }

    @Override
    public int scoreOf(long userId) {
        List<Integer> list = jdbc.queryForList(
                "SELECT score FROM credit_score WHERE user_id = ?", Integer.class, userId);
        return list.isEmpty() ? CreditEventType.BASE_SCORE : list.get(0);
    }

    @Override
    public Optional<CreditScore> find(long userId) {
        List<CreditScore> list = jdbc.query(
                "SELECT user_id, score, version FROM credit_score WHERE user_id = ?",
                (rs, n) -> new CreditScore(rs.getLong("user_id"), rs.getInt("score"), rs.getLong("version")),
                userId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 事件流水 + 分数快照在同一事务里完成；已有业务事务时加入它，
     * 超时回退等无外层事务的调用也必须原子提交这两次写入。
     *
     * 同一用户先锁 credit_score 行，业务事件按 created_at/id 顺序回放最近 30 天。
     * 这样实时更新和每日校准都逐次截断到 [0,100]，达到上限后再扣分也不会
     * 被未展示的“多余正分”抵消。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean applyEvent(CreditEvent event) {
        // This measures both INSERT IGNORE and the locking read: either statement may wait
        // for another transaction on the same user's score row.
        long acquireStart = System.nanoTime();
        try {
            jdbc.update("INSERT IGNORE INTO credit_score (user_id, score, version) VALUES (?, ?, 0)",
                    event.userId(), CreditEventType.BASE_SCORE);
            jdbc.queryForObject("SELECT score FROM credit_score WHERE user_id = ? FOR UPDATE",
                    Integer.class, event.userId());
        } finally {
            scoreAcquireTimer.record(System.nanoTime() - acquireStart, TimeUnit.NANOSECONDS);
        }
        try {
            jdbc.update("""
                    INSERT INTO credit_event (id, biz_no, user_id, type, delta, ref_type, ref_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    event.id(), event.bizNo(), event.userId(), event.type().name(),
                    event.delta(), event.refType(), event.refId());
        } catch (DuplicateKeyException e) {
            // biz_no 已存在：重复事件，幂等跳过（不更新分数）
            return false;
        }
        int score = applyReplayTimer.record(() ->
                replayScore(event.userId(), CreditEventType.WINDOW_DAYS, true));
        jdbc.update("UPDATE credit_score SET score = ?, version = version + 1 WHERE user_id = ?",
                score, event.userId());
        return true;
    }

    private int replayScore(long userId, int windowDays, boolean currentRead) {
        String sql = """
                SELECT delta FROM credit_event
                 WHERE user_id = ? AND created_at >= DATE_SUB(NOW(3), INTERVAL ? DAY)
                 ORDER BY created_at, id
                """ + (currentRead ? " FOR UPDATE" : "");
        CreditScore replay = CreditScore.initial(userId);
        for (Integer delta : jdbc.queryForList(sql, Integer.class, userId, windowDays)) {
            replay.apply(delta);
        }
        return replay.score();
    }

    @Override
    public List<CreditEvent> recentEvents(long userId, int days, int limit) {
        return jdbc.query("""
                SELECT id, biz_no, user_id, type, delta, ref_type, ref_id, created_at
                  FROM credit_event
                 WHERE user_id = ? AND created_at >= ?
                 ORDER BY created_at DESC, id DESC
                 LIMIT ?
                """,
                (rs, n) -> new CreditEvent(
                        rs.getLong("id"), rs.getString("biz_no"), rs.getLong("user_id"),
                        CreditEventType.valueOf(rs.getString("type")), rs.getInt("delta"),
                        rs.getString("ref_type"), rs.getLong("ref_id"),
                        rs.getTimestamp("created_at").toInstant()),
                userId, Timestamp.from(Instant.now().minus(days, ChronoUnit.DAYS)), limit);
    }

    @Override
    public int windowDelta(long userId, int windowDays) {
        Integer sum = jdbc.queryForObject("""
                SELECT COALESCE(SUM(delta), 0) FROM credit_event
                 WHERE user_id = ? AND created_at >= ?
                """, Integer.class, userId,
                Timestamp.from(Instant.now().minus(windowDays, ChronoUnit.DAYS)));
        return sum == null ? 0 : sum;
    }

    @Override
    public int calibrateScores(int windowDays, int limit) {
        List<Long> users = jdbc.queryForList("""
                SELECT user_id FROM credit_score
                UNION
                SELECT user_id FROM credit_event
                 WHERE created_at >= DATE_SUB(NOW(3), INTERVAL ? DAY)
                ORDER BY user_id
                """, Long.class, windowDays);
        int changed = 0;
        for (Long userId : users) {
            CalibrationRow row = recalculateOne(userId, windowDays);
            if (row.oldScore() == row.newScore()) continue;
            // 查询快照与更新之间若有业务事件，其增量更新必递增 version。
            // CAS 冲突后按最新事件重算，避免把旧窗口结果覆盖新事件。
            CalibrationRow current = row;
            for (int attempt = 0; attempt < 3 && current.oldScore() != current.newScore(); attempt++) {
                if (writeCalibration(current) == 1) {
                    changed++;
                    break;
                }
                current = recalculateOne(userId, windowDays);
            }
            if (changed >= limit) break;
        }
        return changed;
    }

    private int writeCalibration(CalibrationRow row) {
        if (row.oldVersion() == 0) {
            return jdbc.update("""
                    INSERT IGNORE INTO credit_score (user_id, score, version)
                    VALUES (?, ?, 1)
                    """, row.userId(), row.newScore());
        }
        return jdbc.update("""
                UPDATE credit_score SET score = ?, version = version + 1
                 WHERE user_id = ? AND version = ?
                """, row.newScore(), row.userId(), row.oldVersion());
    }

    private CalibrationRow recalculateOne(long userId, int windowDays) {
        CreditScore old = find(userId).orElse(CreditScore.initial(userId));
        return new CalibrationRow(userId, old.score(), old.version(),
                replayScore(userId, windowDays, false));
    }

    private record CalibrationRow(long userId, int oldScore, long oldVersion, int newScore) {}
}
