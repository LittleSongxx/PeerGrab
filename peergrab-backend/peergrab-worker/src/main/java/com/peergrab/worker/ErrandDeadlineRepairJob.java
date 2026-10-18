package com.peergrab.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 截止时间缺失数据修复。
 *
 * <p>超时扫描与 CAS 都依赖 {@code confirm_deadline_at}/{@code auto_settle_deadline_at}；
 * 历史脏数据或人工修数若把时间列写成 NULL，任务会永远不进入超时/自动结算，
 * 托管资金静默冻结而对账也发现不了。这里按领域层的回退口径
 * （locked_at/delivered_at + 配置时限）补回时间列，并持续暴露仍无法修复的行数。</p>
 */
@Component
public class ErrandDeadlineRepairJob {

    private static final Logger log = LoggerFactory.getLogger(ErrandDeadlineRepairJob.class);
    private static final int BATCH_LIMIT = 500;

    private final JdbcTemplate jdbc;
    private final int confirmSeconds;
    private final int autoSettleSeconds;

    @Autowired
    public ErrandDeadlineRepairJob(JdbcTemplate jdbc,
                                   @Value("${peergrab.timeout.confirm-seconds:300}") int confirmSeconds,
                                   @Value("${peergrab.settle.auto-settle-seconds:86400}") int autoSettleSeconds) {
        this.jdbc = jdbc;
        this.confirmSeconds = Math.max(1, confirmSeconds);
        this.autoSettleSeconds = Math.max(1, autoSettleSeconds);
    }

    @Scheduled(initialDelayString = "${peergrab.deadline-repair.initial-delay-ms:30000}",
            fixedDelayString = "${peergrab.deadline-repair.interval-ms:300000}",
            scheduler = "maintenanceTaskScheduler")
    public void repair() {
        int confirmFixed;
        int settleFixed;
        try {
            confirmFixed = jdbc.update("""
                    UPDATE errand
                       SET confirm_deadline_at = DATE_ADD(locked_at, INTERVAL ? SECOND)
                     WHERE status = 'LOCKED'
                       AND confirm_deadline_at IS NULL
                       AND locked_at IS NOT NULL
                     LIMIT ?
                    """, confirmSeconds, BATCH_LIMIT);
            settleFixed = jdbc.update("""
                    UPDATE errand
                       SET auto_settle_deadline_at = DATE_ADD(delivered_at, INTERVAL ? SECOND)
                     WHERE status = 'DELIVERED'
                       AND auto_settle_deadline_at IS NULL
                       AND delivered_at IS NOT NULL
                     LIMIT ?
                    """, autoSettleSeconds, BATCH_LIMIT);
        } catch (RuntimeException e) {
            log.warn("截止时间缺失修复失败，下一轮重试", e);
            return;
        }
        if (confirmFixed > 0 || settleFixed > 0) {
            log.warn("截止时间缺失修复完成 confirm={} autoSettle={}，请排查写入这些行子的路径",
                    confirmFixed, settleFixed);
        }
        try {
            Integer unfixable = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM errand
                     WHERE (status = 'LOCKED' AND confirm_deadline_at IS NULL)
                        OR (status = 'DELIVERED' AND auto_settle_deadline_at IS NULL)
                    """, Integer.class);
            if (unfixable != null && unfixable > 0) {
                log.error("仍有 {} 条 LOCKED/DELIVERED 任务缺少截止时间（locked_at/delivered_at 也为空），"
                        + "这些任务不会自动流转/结算，必须人工修数", unfixable);
            }
        } catch (RuntimeException e) {
            log.warn("统计缺失截止时间任务失败", e);
        }
    }
}
