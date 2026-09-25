package com.peergrab.application.usecase.query;

import com.peergrab.domain.errand.model.Errand;
import com.peergrab.domain.errand.ports.ErrandCachePort;
import com.peergrab.domain.errand.ports.ErrandRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
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
 * 等于把缓存击穿转化成了线程池耗尽。返回旧值的代价是最多几百毫秒的数据陈旧，
 * 而任务详情这个场景完全可以接受（名额判定与抢单裁决绝不读缓存，走 Redis Lua + DB）。
 *
 * dbLoadCount 用于测试断言"回源恰好几次"，生产环境也可作为指标暴露。
 */
@Service
public class GetErrandDetailUseCase {

    private static final Logger log = LoggerFactory.getLogger(GetErrandDetailUseCase.class);

    private final ErrandRepository errandRepository;
    private final ErrandCachePort cache;

    /** 回源计数（测试用来验证单飞回填与空值缓存） */
    private final AtomicLong dbLoadCount = new AtomicLong();
    private final AtomicLong cacheHitCount = new AtomicLong();
    private final AtomicLong requestCount = new AtomicLong();

    public GetErrandDetailUseCase(ErrandRepository errandRepository, ErrandCachePort cache) {
        this.errandRepository = errandRepository;
        this.cache = cache;
    }

    /** 返回详情 JSON（presentation 层直接透出）；empty 表示任务不存在 */
    public Optional<String> detailJson(long errandId) {
        requestCount.incrementAndGet();

        Optional<ErrandCachePort.CachedErrand> cached = cache.get(errandId);
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
            if (cache.tryAcquireRebuild(errandId)) {
                try {
                    return reload(errandId, false);
                } finally {
                    cache.releaseRebuild(errandId);
                }
            }
            cacheHitCount.incrementAndGet();
            log.debug("逻辑过期但未抢到重建权，返回旧值 errandId={}", errandId);
            return Optional.of(c.payloadJson());
        }

        // Bloom 与 MySQL 非同一事务，negative 必须向数据库核实，避免 Redis 丢失、
        // 重建中的部分过滤器或发布后登记失败把真实任务误判为 404。
        boolean bloomNegative = !cache.mightExist(errandId);
        if (cache.tryAcquireRebuild(errandId)) {
            try {
                // 锁前读到 miss 后，另一请求可能已完成回填。
                Optional<ErrandCachePort.CachedErrand> filled = cache.get(errandId);
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
        // 冷 miss 没有旧值可返回，短暂等待持锁者回填；超时后自行回源保证可用性。
        for (int attempt = 0; attempt < 5; attempt++) {
            if (Thread.currentThread().isInterrupted()) break;
            LockSupport.parkNanos(10_000_000L);
            Optional<ErrandCachePort.CachedErrand> filled = cache.get(errandId);
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
        Optional<Errand> found = errandRepository.findById(errandId);
        if (found.isEmpty()) {
            // 不存在的 id 经 MySQL 确认后写短期空值缓存，避免同一个 id 反复打 DB。
            cache.putEmpty(errandId);
            return Optional.empty();
        }
        if (bloomNegative) {
            log.warn("布隆索引遗漏真实任务，已向 MySQL 核实并尝试补登记 errandId={}", errandId);
            cache.registerExisting(errandId);
        }
        String json = toJson(found.get());
        cache.put(errandId, json);
        return Optional.of(json);
    }

    /**
     * 详情 JSON。手写而不用 Jackson：字段固定且要与 ErrandController.toCard 对齐，
     * 手写能保证缓存里存的就是接口要返回的形状，不会因序列化配置变化而漂移。
     * id 一律用字符串（雪花 ID 超过 JS 安全整数，见 P4 踩坑第 11 条）。
     */
    private String toJson(Errand e) {
        return String.format(
                "{\"id\":\"%d\",\"title\":\"%s\",\"status\":\"%s\",\"type\":\"%s\","
                        + "\"rewardCents\":%d,\"slotTotal\":%d,\"slotTaken\":%d,"
                        + "\"publisherId\":\"%d\",\"grabberId\":\"%d\",\"round\":%d,\"version\":%d}",
                e.id(), escape(e.title()), e.status().name(), e.type().name(),
                e.reward().cents(), e.slotTotal(), e.slotTaken(),
                e.publisherId(), e.grabberId() == null ? -1L : e.grabberId(),
                e.round(), e.version());
    }

    private String escape(String s) {
        if (s == null) return "";
        StringBuilder escaped = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    public long dbLoadCount() { return dbLoadCount.get(); }
    public long cacheHitCount() { return cacheHitCount.get(); }
    public long requestCount() { return requestCount.get(); }

    /** 命中率：S3 压测报告要用 */
    public double hitRate() {
        long total = requestCount.get();
        return total == 0 ? 0 : (double) cacheHitCount.get() / total;
    }

    public void resetStats() {
        dbLoadCount.set(0);
        cacheHitCount.set(0);
        requestCount.set(0);
    }
}
