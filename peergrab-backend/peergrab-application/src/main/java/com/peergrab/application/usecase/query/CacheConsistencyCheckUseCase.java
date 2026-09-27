package com.peergrab.application.usecase.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peergrab.domain.errand.model.Errand;
import com.peergrab.domain.errand.ports.ErrandCachePort;
import com.peergrab.domain.errand.ports.ErrandQueryPort;
import com.peergrab.domain.errand.ports.ErrandRepository;
import com.peergrab.domain.errand.ports.SyncDiffRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 缓存一致性校验 job（架构文档 9.6 的落地）。
 *
 * 每 5 分钟随机抽样 N 个任务，比对 MySQL 与 Redis 缓存的关键字段，
 * 不一致的落 sync_diff 表并主动删缓存修正。
 *
 * ── 定位 ──
 * 这是兜底机制，不是主一致性手段。主链路是 afterCommit 删除 + 延迟双删 + TTL；
 * 本 job 负责检出主链路漏掉的场景（删除失败、Redis 故障期降级残留、未知 bug）。
 * sync_diff 正常必须为空——有记录就要查，它是"缓存链路健康"的哨兵。
 *
 * ── 抽样方式 ──
 * ORDER BY RAND() 在万级数据量没问题；十万级以上要改成按 id 分段随机，
 * 避免全表排序。校园量级无压力，注释留痕。
 */
@Service
public class CacheConsistencyCheckUseCase {

    private static final Logger log = LoggerFactory.getLogger(CacheConsistencyCheckUseCase.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ErrandQueryPort queryPort;
    private final ErrandRepository errandRepository;
    private final ErrandCachePort cache;
    private final SyncDiffRepository syncDiffRepository;
    private final int sampleSize;

    public CacheConsistencyCheckUseCase(ErrandQueryPort queryPort,
                                    ErrandRepository errandRepository,
                                    ErrandCachePort cache,
                                    SyncDiffRepository syncDiffRepository,
                                    @Value("${peergrab.cache.check-sample-size:100}") int sampleSize) {
        this.queryPort = queryPort;
        this.errandRepository = errandRepository;
        this.cache = cache;
        this.syncDiffRepository = syncDiffRepository;
        this.sampleSize = sampleSize;
    }

    /**
     * 执行一轮抽样校验，返回差异数。
     * 调度由 worker 的 CacheConsistencyCheckScheduler 触发；
     * 放在 application 层而不是 worker，是为了让集成测试能直接注入验证。
     */
    public int runOnce() {
        List<Long> ids = queryPort.sampleIds(sampleSize);
        int diffs = 0;
        Instant now = Instant.now();

        for (Long id : ids) {
            // 缓存里没有就不比对：未命中不是不一致（下次读会回源）
            var cached = cache.get(id);
            if (cache.isDegraded()) {
                log.warn("Redis 故障，本轮跳过缓存一致性校验 errandId={}", id);
                break;
            }
            if (cached.isEmpty() || cached.get().isEmpty()) {
                continue;
            }
            Errand db = errandRepository.findById(id).orElse(null);
            if (db == null) {
                // DB 里没了缓存还在：任务本项目不会物理删除，出现即异常
                boolean fixed = tryEvict(id);
                syncDiffRepository.record(now, id, "existence", "MISSING", "PRESENT", fixed);
                diffs++;
                continue;
            }
            String cacheJson = cached.get().payloadJson();
            diffs += compareAndFix(now, id, db, cacheJson);
        }
        if (diffs > 0) {
            log.warn("一致性校验检出 {} 处差异（已落 sync_diff；修复状态见 fixed 字段）", diffs);
        }
        return diffs;
    }

    private int compareAndFix(Instant now, long id, Errand db, String cacheJson) {
        int diffs = 0;
        JsonNode cached;
        try {
            cached = JSON.readTree(cacheJson);
        } catch (JsonProcessingException e) {
            log.warn("任务详情缓存 JSON 损坏 errandId={}", id, e);
            cached = null;
        }
        String cacheStatus = field(cached, "status");
        String cacheVersion = field(cached, "version");
        String cacheReward = field(cached, "rewardCents");

        boolean statusDiff = !db.status().name().equals(cacheStatus);
        boolean versionDiff = !String.valueOf(db.version()).equals(cacheVersion);
        boolean rewardDiff = !String.valueOf(db.reward().cents()).equals(cacheReward);
        diffs = (statusDiff ? 1 : 0) + (versionDiff ? 1 : 0) + (rewardDiff ? 1 : 0);
        if (diffs == 0) return 0;

        // 先删除，再记修复结果。失败时保留 fixed=false 供后续排查及重试。
        boolean fixed = tryEvict(id);
        if (statusDiff) syncDiffRepository.record(now, id, "status", db.status().name(), cacheStatus, fixed);
        if (versionDiff) syncDiffRepository.record(now, id, "version", String.valueOf(db.version()), cacheVersion, fixed);
        if (rewardDiff) syncDiffRepository.record(now, id, "reward_amount", String.valueOf(db.reward().cents()), cacheReward, fixed);
        return diffs;
    }

    private boolean tryEvict(long id) {
        try {
            cache.evict(id);
            return true;
        } catch (RuntimeException e) {
            log.error("一致性校验删除缓存失败 errandId={}", id, e);
            return false;
        }
    }

    private String field(JsonNode node, String name) {
        if (node == null || !node.isObject()) return null;
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }
}
