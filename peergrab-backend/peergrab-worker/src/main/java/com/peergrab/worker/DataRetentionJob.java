package com.peergrab.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 数据保留清理任务。
 *
 * <p>只增不减的表长期运行会持续膨胀：已发送的延迟消息/资金事件、发布幂等登记、
 * 已读通知、对账与缓存差异记录都属于"超过保留期即可删"的派生数据。
 * 这里按有界批次删除，避免一条大 DELETE 长时间持锁；资金流水、托管单、
 * 状态日志等审计与账务事实不做删除。</p>
 */
@Component
public class DataRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionJob.class);

    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final int batchSize;
    private final int maxBatches;
    private final int localMessageDays;
    private final int fundOutboxDays;
    private final int publishRequestDays;
    private final int notificationDays;
    private final int syncDiffDays;

    @Autowired
    public DataRetentionJob(JdbcTemplate jdbc,
                            @Value("${peergrab.retention.enabled:true}") boolean enabled,
                            @Value("${peergrab.retention.batch-size:500}") int batchSize,
                            @Value("${peergrab.retention.max-batches:20}") int maxBatches,
                            @Value("${peergrab.retention.local-message-days:7}") int localMessageDays,
                            @Value("${peergrab.retention.fund-outbox-days:30}") int fundOutboxDays,
                            @Value("${peergrab.retention.publish-request-days:7}") int publishRequestDays,
                            @Value("${peergrab.retention.notification-days:180}") int notificationDays,
                            @Value("${peergrab.retention.sync-diff-days:90}") int syncDiffDays) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.batchSize = Math.max(1, batchSize);
        this.maxBatches = Math.max(1, maxBatches);
        this.localMessageDays = Math.max(1, localMessageDays);
        this.fundOutboxDays = Math.max(1, fundOutboxDays);
        this.publishRequestDays = Math.max(1, publishRequestDays);
        this.notificationDays = Math.max(1, notificationDays);
        this.syncDiffDays = Math.max(1, syncDiffDays);
    }

    @Scheduled(cron = "${peergrab.retention.cron:0 30 4 * * ?}", scheduler = "maintenanceTaskScheduler")
    public void run() {
        if (!enabled) return;
        int total = 0;
        total += prune("local_message(SENT)", """
                DELETE FROM local_message
                 WHERE status = 'SENT' AND updated_at < DATE_SUB(NOW(3), INTERVAL ? DAY)
                 LIMIT ?
                """, localMessageDays);
        total += prune("fund_event_outbox(SENT)", """
                DELETE FROM fund_event_outbox
                 WHERE status = 'SENT' AND updated_at < DATE_SUB(NOW(3), INTERVAL ? DAY)
                 LIMIT ?
                """, fundOutboxDays);
        total += prune("publish_request", """
                DELETE FROM publish_request
                 WHERE created_at < DATE_SUB(NOW(3), INTERVAL ? DAY)
                 LIMIT ?
                """, publishRequestDays);
        total += prune("notification(read)", """
                DELETE FROM notification
                 WHERE read_flag = 1 AND created_at < DATE_SUB(NOW(3), INTERVAL ? DAY)
                 LIMIT ?
                """, notificationDays);
        // recon_diff 是"账实不一致"的审计证据，不做自动删除：保留到人工确认处理完毕。
        total += prune("sync_diff", """
                DELETE FROM sync_diff
                 WHERE check_time < DATE_SUB(NOW(3), INTERVAL ? DAY)
                 LIMIT ?
                """, syncDiffDays);
        if (total > 0) log.info("数据保留清理完成 removed={}", total);
    }

    /** 单表按批删除，单轮最多 maxBatches 批；异常只告警，不影响其它表。 */
    private int prune(String table, String sql, int days) {
        int removedTotal = 0;
        try {
            for (int batch = 0; batch < maxBatches; batch++) {
                int removed = jdbc.update(sql, days, batchSize);
                removedTotal += removed;
                if (removed < batchSize) break;
            }
        } catch (RuntimeException e) {
            log.warn("数据保留清理失败 table={}，下一轮重试", table, e);
        }
        return removedTotal;
    }

    int batchSize() {
        return batchSize;
    }
}
