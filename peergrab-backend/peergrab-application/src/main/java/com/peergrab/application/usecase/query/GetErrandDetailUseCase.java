package com.peergrab.application.usecase.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peergrab.domain.errand.model.Errand;
import com.peergrab.domain.errand.ports.ErrandCachePort;
import com.peergrab.domain.errand.ports.ErrandRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 任务详情查询：Cache Aside 读路径的三段逻辑。
 *
 * ── 读路径 ──
 *   1. 读缓存命中且未逻辑过期 → 直接返回
 *   2. 命中但逻辑过期 → 抢重建权：抢到的回源重建，抢不到的返回旧值（防击穿）
 *   3. 未命中 → 抢重建权 → 回源 DB → 回填（查不到则写空值缓存）
 *
 * ── 为什么逻辑过期时"返回旧值"而不是"阻塞等重建" ──
 * 热点任务缓存到期瞬间会有几百个并发读。阻塞等待会把这几百个线程挂在锁上，
 * 等于把缓存击穿转化成了线程池耗尽。返回旧值的陈旧时间受物理 TTL 和重建成败约束，
 * 重建持续失败时可能达到分钟级；名额判定与抢单裁决不读这个展示缓存。
 *
 * dbLoadCount 用于测试断言"回源恰好几次"，生产环境也可作为指标暴露。
 */
@Service
public class GetErrandDetailUseCase {

    private static final Logger log = LoggerFactory.getLogger(GetErrandDetailUseCase.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ErrandRepository errandRepository;
    private final ErrandCachePort cache;
    private final Timer cacheLookupTimer;
    private final Timer rebuildLockTimer;
    private final Timer databaseLoadTimer;
    private final Timer cacheFillTimer;

    /** 回源计数（测试用来验证单飞回填与空值缓存） */
    private final AtomicLong dbLoadCount = new AtomicLong();
    private final AtomicLong cacheHitCount = new AtomicLong();
    private final AtomicLong requestCount = new AtomicLong();
    private final AtomicLong staleReturnCount = new AtomicLong();
    private final AtomicLong degradedReadCount = new AtomicLong();
    private final AtomicLong degradedLocalHitCount = new AtomicLong();

    @Autowired
    public GetErrandDetailUseCase(ErrandRepository errandRepository, ErrandCachePort cache,
                                  MeterRegistry registry) {
        this.errandRepository = errandRepository;
        this.cache = cache;
        this.cacheLookupTimer = timer(registry, "peergrab.cache.detail.lookup",
                "Redis detail lookup, including a failed lookup before database fallback");
        this.rebuildLockTimer = timer(registry, "peergrab.cache.detail.rebuild.lock",
                "Attempt to acquire the detail rebuild lock");
        this.databaseLoadTimer = timer(registry, "peergrab.cache.detail.database.load",
                "MySQL detail load after a cache miss or degradation");
        this.cacheFillTimer = timer(registry, "peergrab.cache.detail.fill",
                "Redis detail write after a database load");
    }

    /** Keeps lightweight use-case tests independent of Spring's metrics configuration. */
    public GetErrandDetailUseCase(ErrandRepository errandRepository, ErrandCachePort cache) {
        this(errandRepository, cache, new SimpleMeterRegistry());
    }

    private static Timer timer(MeterRegistry registry, String name, String description) {
        return Timer.builder(name).description(description)
                .serviceLevelObjectives(Duration.ofMillis(1), Duration.ofMillis(5),
                        Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50),
                        Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500),
                        Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(5))
                .register(registry);
    }

    private Optional<ErrandCachePort.CachedErrand> cacheLookup(long errandId) {
        long started = System.nanoTime();
        try {
            return cache.get(errandId);
        } finally {
            cacheLookupTimer.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private boolean acquireRebuild(long errandId) {
        long started = System.nanoTime();
        try {
            return cache.tryAcquireRebuild(errandId);
        } finally {
            rebuildLockTimer.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private Optional<Errand> loadFromDatabase(long errandId) {
        long started = System.nanoTime();
        try {
            return errandRepository.findById(errandId);
        } finally {
            databaseLoadTimer.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private void fillCache(Runnable operation) {
        long started = System.nanoTime();
        try {
            operation.run();
        } finally {
            cacheFillTimer.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    /** 返回详情 JSON（presentation 层直接透出）；empty 表示任务不存在 */
    public Optional<String> detailJson(long errandId) {
        requestCount.incrementAndGet();

        Optional<ErrandCachePort.CachedErrand> cached = cacheLookup(errandId);
        if (cache.isDegraded()) {
            degradedReadCount.incrementAndGet();
            // Redis may be down after this process already served the task
            // successfully. The adapter keeps a bounded, very short-lived
            // local copy for this branch only; never use it after logical
            // expiry, and continue to MySQL when no safe copy exists.
            if (cached.isPresent()) {
                ErrandCachePort.CachedErrand degradedValue = cached.get();
                if (!degradedValue.isEmpty() && !degradedValue.logicallyExpired()) {
                    degradedLocalHitCount.incrementAndGet();
                    return Optional.of(degradedValue.payloadJson());
                }
            }
            return reload(errandId, false);
        }
        if (cached.isPresent()) {
            ErrandCachePort.CachedErrand c = cached.get();
            if (c.isEmpty()) {
                // 空值缓存命中：说明不久前查过且确实不存在，直接返回
                cacheHitCount.incrementAndGet();
                return Optional.empty();
            }
            if (!c.logicallyExpired()) {
                cacheHitCount.incrementAndGet();
                return Optional.of(c.payloadJson());
            }
            // 逻辑过期：抢到重建权的去重建，抢不到的先返回旧值
            if (acquireRebuild(errandId)) {
                try {
                    return reload(errandId, false);
                } finally {
                    cache.releaseRebuild(errandId);
                }
            }
            if (cache.isDegraded()) {
                degradedReadCount.incrementAndGet();
                return reload(errandId, false);
            }
            cacheHitCount.incrementAndGet();
            staleReturnCount.incrementAndGet();
            log.debug("逻辑过期但未抢到重建权，返回旧值 errandId={}", errandId);
            return Optional.of(c.payloadJson());
        }

        // Bloom 与 MySQL 非同一事务，negative 必须向数据库核实，避免 Redis 丢失、
        // 重建中的部分过滤器或发布后登记失败把真实任务误判为 404。
        boolean bloomNegative = !cache.mightExist(errandId);
        if (cache.isDegraded()) {
            degradedReadCount.incrementAndGet();
            return reload(errandId, false);
        }
        if (acquireRebuild(errandId)) {
            try {
                // 锁前读到 miss 后，另一请求可能已完成回填。
                Optional<ErrandCachePort.CachedErrand> filled = cacheLookup(errandId);
                if (filled.isPresent() && !filled.get().logicallyExpired()) {
                    cacheHitCount.incrementAndGet();
                    return filled.get().isEmpty()
                            ? Optional.empty() : Optional.of(filled.get().payloadJson());
                }
                return reload(errandId, bloomNegative);
            } finally {
                cache.releaseRebuild(errandId);
            }
        }
        if (cache.isDegraded()) {
            degradedReadCount.incrementAndGet();
            return reload(errandId, false);
        }
        // 冷 miss 没有旧值可返回，短暂等待持锁者回填；超时后自行回源保证可用性。
        for (int attempt = 0; attempt < 5; attempt++) {
            if (Thread.currentThread().isInterrupted()) break;
            LockSupport.parkNanos(10_000_000L);
            Optional<ErrandCachePort.CachedErrand> filled = cacheLookup(errandId);
            if (cache.isDegraded()) {
                degradedReadCount.incrementAndGet();
                return reload(errandId, false);
            }
            if (filled.isPresent()) {
                cacheHitCount.incrementAndGet();
                return filled.get().isEmpty()
                        ? Optional.empty() : Optional.of(filled.get().payloadJson());
            }
        }
        return reload(errandId, bloomNegative);
    }

    private Optional<String> reload(long errandId, boolean bloomNegative) {
        dbLoadCount.incrementAndGet();
        Optional<Errand> found = loadFromDatabase(errandId);
        if (found.isEmpty()) {
            // 不存在的 id 经 MySQL 确认后写短期空值缓存，避免同一个 id 反复打 DB。
            if (!cache.isDegraded()) fillCache(() -> cache.putEmpty(errandId));
            return Optional.empty();
        }
        if (bloomNegative && !cache.isDegraded()) {
            log.warn("布隆索引遗漏真实任务，已向 MySQL 核实并尝试补登记 errandId={}", errandId);
            cache.registerExisting(errandId);
        }
        String json = toDetailJson(found.get());
        if (!cache.isDegraded()) fillCache(() -> cache.put(errandId, json));
        return Optional.of(json);
    }

    /** ID is explicitly a string so the browser never rounds Snowflake IDs. */
    static String toDetailJson(Errand e) {
        Detail detail = new Detail(String.valueOf(e.id()), e.title() == null ? "" : e.title(),
                e.status().name(), e.type().name(), e.reward().cents(), e.slotTotal(),
                e.slotTaken(), String.valueOf(e.publisherId()),
                String.valueOf(e.grabberId() == null ? -1L : e.grabberId()), e.round(), e.version());
        try {
            return JSON.writeValueAsString(detail);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize errand detail id=" + e.id(), ex);
        }
    }

    private record Detail(String id, String title, String status, String type,
                          long rewardCents, int slotTotal, int slotTaken, String publisherId,
                          String grabberId, int round, long version) {}

    public long dbLoadCount() { return dbLoadCount.get(); }
    public long cacheHitCount() { return cacheHitCount.get(); }
    public long requestCount() { return requestCount.get(); }
    public long staleReturnCount() { return staleReturnCount.get(); }
    public long degradedReadCount() { return degradedReadCount.get(); }
    public long degradedLocalHitCount() { return degradedLocalHitCount.get(); }

    /** 命中率：S3 压测报告要用 */
    public double hitRate() {
        long total = requestCount.get();
        return total == 0 ? 0 : (double) cacheHitCount.get() / total;
    }

    public void resetStats() {
        dbLoadCount.set(0);
        cacheHitCount.set(0);
        requestCount.set(0);
        staleReturnCount.set(0);
        degradedReadCount.set(0);
        degradedLocalHitCount.set(0);
    }
}
