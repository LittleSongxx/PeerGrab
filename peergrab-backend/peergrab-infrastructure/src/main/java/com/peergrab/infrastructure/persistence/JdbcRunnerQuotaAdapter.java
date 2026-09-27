package com.peergrab.infrastructure.persistence;

import com.peergrab.domain.errand.ports.RunnerQuotaPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class JdbcRunnerQuotaAdapter implements RunnerQuotaPort {

    private final JdbcTemplate jdbc;

    public JdbcRunnerQuotaAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean lockAndHasCapacity(long runnerId, int maxOngoing) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("跑腿额度检查必须在数据库事务内执行");
        }
        // 首次分配创建锁行；重复键走 UPDATE，直接取得排他锁。
        // INSERT IGNORE 在首次并发插入时可能让等待者取得共享锁，随后
        // SELECT FOR UPDATE 升级互相等待而死锁，因此这里不能用 IGNORE。
        jdbc.update("""
                INSERT INTO runner_quota_lock (runner_id) VALUES (?)
                ON DUPLICATE KEY UPDATE runner_id = runner_id
                """, runnerId);
        jdbc.queryForObject("SELECT runner_id FROM runner_quota_lock WHERE runner_id = ? FOR UPDATE",
                Long.class, runnerId);
        Integer active = jdbc.queryForObject("""
                SELECT COUNT(*) FROM errand
                 WHERE grabber_id = ? AND status IN ('LOCKED', 'ACCEPTED', 'PICKED_UP')
                """, Integer.class, runnerId);
        return active != null && active < maxOngoing;
    }
}
