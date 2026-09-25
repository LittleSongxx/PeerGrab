package com.peergrab.presentation;

import com.peergrab.application.usecase.query.BloomRebuildUseCase;
import com.peergrab.application.usecase.query.CacheConsistencyCheckUseCase;
import com.peergrab.application.usecase.query.GetErrandDetailUseCase;
import com.peergrab.shared.Result;
import com.peergrab.presentation.auth.CurrentUser;
import com.peergrab.shared.BizException;
import com.peergrab.shared.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 内部观测端点：压测取数与运维动作用。
 *
 * Internal operations require the configured arbitrator identity as well as authentication.
 */
@RestController
@RequestMapping("/api/internal")
public class InternalController {

    private final GetErrandDetailUseCase detailUseCase;
    private final CacheConsistencyCheckUseCase checkUseCase;
    private final BloomRebuildUseCase bloomRebuildUseCase;
    private final long arbitratorId;

    public InternalController(GetErrandDetailUseCase detailUseCase,
                              CacheConsistencyCheckUseCase checkUseCase,
                              BloomRebuildUseCase bloomRebuildUseCase,
                              @Value("${peergrab.auth.arbitrator-id:9001}") long arbitratorId) {
        this.detailUseCase = detailUseCase;
        this.checkUseCase = checkUseCase;
        this.bloomRebuildUseCase = bloomRebuildUseCase;
        this.arbitratorId = arbitratorId;
    }

    private void requireArbitrator() {
        if (CurrentUser.get() != arbitratorId) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "仅仲裁员可以执行内部操作");
        }
    }

    /** 缓存统计：S3 压测的命中率取这里 */
    @GetMapping("/cache-stats")
    public Result<Map<String, Object>> cacheStats() {
        requireArbitrator();
        return Result.ok(Map.of(
                "requests", detailUseCase.requestCount(),
                "cacheHits", detailUseCase.cacheHitCount(),
                "dbLoads", detailUseCase.dbLoadCount(),
                "hitRate", detailUseCase.hitRate()));
    }

    @PostMapping("/cache-stats/reset")
    public Result<Void> resetStats() {
        requireArbitrator();
        detailUseCase.resetStats();
        return Result.ok(null);
    }

    /** 手动触发一轮一致性校验，返回差异数 */
    @PostMapping("/cache-check")
    public Result<Map<String, Object>> cacheCheck() {
        requireArbitrator();
        return Result.ok(Map.of("diffs", checkUseCase.runOnce()));
    }

    /** 手动重建布隆（批量造数后调用） */
    @PostMapping("/bloom-rebuild")
    public Result<Map<String, Object>> bloomRebuild() {
        requireArbitrator();
        return Result.ok(Map.of("registered", bloomRebuildUseCase.rebuild()));
    }
}
