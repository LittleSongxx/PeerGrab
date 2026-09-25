package com.peergrab.application.usecase.query;

import com.peergrab.domain.errand.ports.ErrandCachePort;
import com.peergrab.domain.errand.ports.ErrandQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 布隆重建：把存量任务 id 全量灌入布隆过滤器。
 *
 * 触发场景：
 *   1. worker 启动时（BloomRebuildJob）
 *   2. 绕过发布用例的批量造数之后（数据迁移、压测 seed、DBA 修数）——
 *      这是 P5 实测确认的运维约束：布隆只认"登记过的 id"，
 *      绕过应用写入的数据必须补登记，否则会被防穿透逻辑误杀
 */
@Service
public class BloomRebuildUseCase {

    private static final Logger log = LoggerFactory.getLogger(BloomRebuildUseCase.class);
    private static final int BATCH_SIZE = 1000;

    private final ErrandQueryPort queryPort;
    private final ErrandCachePort cache;

    public BloomRebuildUseCase(ErrandQueryPort queryPort, ErrandCachePort cache) {
        this.queryPort = queryPort;
        this.cache = cache;
    }

    public int rebuild() {
        if (!cache.beginExistenceIndexRebuild()) {
            throw new IllegalStateException("无法撤销布隆就绪标记");
        }
        int total = 0;
        long cursor = Long.MIN_VALUE;
        while (true) {
            List<Long> ids = queryPort.scanIdsAfter(cursor, BATCH_SIZE);
            if (ids.isEmpty()) break;
            for (Long id : ids) {
                if (!cache.registerExisting(id)) {
                    throw new IllegalStateException("布隆登记失败 errandId=" + id);
                }
            }
            total += ids.size();
            cursor = ids.get(ids.size() - 1);
            if (ids.size() < BATCH_SIZE) break;
        }
        if (!cache.completeExistenceIndexRebuild()) {
            throw new IllegalStateException("无法设置布隆就绪标记");
        }
        log.info("布隆重建完成，灌入 {} 个任务 id", total);
        return total;
    }
}
