package com.peergrab.worker;

import com.peergrab.domain.credit.ports.CreditRankingPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/** MySQL 信用分是事实源；定时重建 Redis 榜单以修复校准、超时扣分及 Redis 丢失。 */
@Component
public class CreditRankingRebuildJob {

    private static final Logger log = LoggerFactory.getLogger(CreditRankingRebuildJob.class);

    private final JdbcTemplate jdbc;
    private final CreditRankingPort ranking;

    public CreditRankingRebuildJob(JdbcTemplate jdbc, CreditRankingPort ranking) {
        this.jdbc = jdbc;
        this.ranking = ranking;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void rebuildOnStartup() {
        rebuild();
    }

    @Scheduled(fixedDelayString = "${peergrab.credit.ranking-rebuild-interval-ms:60000}", scheduler = "maintenanceTaskScheduler")
    public void rebuild() {
        try {
            // 包含当前没有结算事件的校区，才能把历史残留榜单清空。
            List<Long> campuses = jdbc.queryForList(
                    "SELECT DISTINCT campus_id FROM errand", Long.class);
            for (Long campusId : campuses) {
                try {
                    List<CreditRankingPort.Entry> entries = jdbc.query("""
                            SELECT ce.user_id, COALESCE(cs.score, 60) AS score
                              FROM credit_event ce
                              JOIN errand e ON e.id = ce.ref_id
                              LEFT JOIN credit_score cs ON cs.user_id = ce.user_id
                             WHERE ce.type = 'SETTLE' AND ce.ref_type = 'ERRAND'
                               AND e.campus_id = ?
                             GROUP BY ce.user_id, cs.score
                            """, (rs, rowNum) -> new CreditRankingPort.Entry(
                            rs.getLong("user_id"), rs.getInt("score")), campusId);
                    ranking.replace(campusId, entries);
                } catch (RuntimeException e) {
                    log.warn("信用榜单重建失败 campusId={}", campusId, e);
                }
            }
            if (!campuses.isEmpty()) log.debug("信用榜单从 MySQL 恢复 campuses={}", campuses.size());
        } catch (RuntimeException e) {
            log.warn("信用榜单恢复扫描失败，下一轮重试", e);
        }
    }
}
