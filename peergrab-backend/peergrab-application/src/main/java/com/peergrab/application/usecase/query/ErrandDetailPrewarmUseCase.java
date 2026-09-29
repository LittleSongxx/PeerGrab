package com.peergrab.application.usecase.query;

import com.peergrab.domain.errand.model.Errand;
import com.peergrab.domain.errand.ports.ErrandCachePort;
import com.peergrab.domain.errand.ports.ErrandRepository;
import com.peergrab.shared.BizException;
import com.peergrab.shared.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 详情缓存的有界、只读预热。
 *
 * 预热只从 MySQL 真值构造展示 JSON，再批量写入缓存；它不参与抢单、结算或
 * 名额裁决。业务写事务仍通过 afterCommit + 延迟双删失效缓存，因此预热失败
 * 或 Redis 暂时不可用只会让后续请求走原有 Cache Aside 回源路径。
 */
@Service
public class ErrandDetailPrewarmUseCase {

    /** 防止内部接口被误用成无界的全表导出或 Redis 大批量写。 */
    static final int MAX_IDS = 1_000;
    private static final int DB_BATCH_SIZE = 250;

    private final ErrandRepository errandRepository;
    private final ErrandCachePort cache;

    public ErrandDetailPrewarmUseCase(ErrandRepository errandRepository, ErrandCachePort cache) {
        this.errandRepository = errandRepository;
        this.cache = cache;
    }

    public Result prewarm(List<Long> requestedIds) {
        long started = System.nanoTime();
        List<Long> ids = normalize(requestedIds);
        if (!cache.isEnabled() || cache.isDegraded()) {
            return result(requestedIds == null ? 0 : requestedIds.size(), ids,
                    0, 0, ids.size(), false, started);
        }

        Map<Long, String> payloads = new LinkedHashMap<>();
        for (int from = 0; from < ids.size(); from += DB_BATCH_SIZE) {
            int to = Math.min(ids.size(), from + DB_BATCH_SIZE);
            for (Errand errand : errandRepository.findByIds(ids.subList(from, to))) {
                payloads.put(errand.id(), GetErrandDetailUseCase.toDetailJson(errand));
            }
        }
        cache.putAll(payloads);
        boolean healthy = !cache.isDegraded();
        int written = healthy ? payloads.size() : 0;
        return result(requestedIds == null ? 0 : requestedIds.size(), ids,
                payloads.size(), written, ids.size() - payloads.size(), healthy, started);
    }

    private List<Long> normalize(List<Long> requestedIds) {
        if (requestedIds == null || requestedIds.isEmpty()) return List.of();
        if (requestedIds.size() > MAX_IDS) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "缓存预热一次最多接受 " + MAX_IDS + " 个任务 ID");
        }
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        for (Long id : requestedIds) {
            if (id == null || id <= 0) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "任务 ID 必须为正数");
            }
            unique.add(id);
        }
        return new ArrayList<>(unique);
    }

    private Result result(int requested, List<Long> ids, int found, int written,
                          int missing, boolean healthy, long started) {
        return new Result(requested, ids.size(), found, written, missing, healthy,
                (System.nanoTime() - started) / 1_000_000L);
    }

    public record Result(int requested, int distinct, int found, int written,
                         int missing, boolean cacheHealthy, long elapsedMs) {}
}
