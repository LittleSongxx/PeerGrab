package com.peergrab.worker;

import com.peergrab.application.usecase.query.BloomRebuildUseCase;
import com.peergrab.domain.errand.ports.ErrandCachePort;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 布隆过滤器重建：worker 启动时把存量任务 id 灌入布隆。
 *
 * ── 为什么需要 ──
 * 布隆只加不减，且只存在于 Redis。两种情况会导致布隆缺失存量 id：
 *   1. Redis 被清空 / 布隆 key 过期或被误删
 *   2. 新环境首次部署
 * 缺失后，详情查询会回源 MySQL 核实，不会误判 404；重建恢复索引完整性。
 *
 * ── 并发保护 ──
 * 多个 worker 实例同时启动时，用 Redisson 锁保证只有一个实例执行重建。
 *
 * 使用主键游标分批读取；就绪标记仅在全部登记成功后恢复。
 */
@Component
public class BloomRebuildJob {

    private static final Logger log = LoggerFactory.getLogger(BloomRebuildJob.class);
    private static final String REBUILD_LOCK = "bloom:rebuild:lock";

    private final BloomRebuildUseCase rebuildUseCase;
    private final ErrandCachePort cache;
    private final RedissonClient redisson;
    private final boolean bloomEnabled;

    public BloomRebuildJob(BloomRebuildUseCase rebuildUseCase,
                           ErrandCachePort cache,
                           RedissonClient redisson,
                           @Value("${peergrab.cache.bloom-enabled:true}") boolean bloomEnabled) {
        this.rebuildUseCase = rebuildUseCase;
        this.cache = cache;
        this.redisson = redisson;
        this.bloomEnabled = bloomEnabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void rebuildOnStartup() {
        ensureReady();
    }

    @Scheduled(fixedDelayString = "${peergrab.cache.bloom-rebuild-interval-ms:300000}", scheduler = "maintenanceTaskScheduler")
    public void ensureReady() {
        if (!bloomEnabled || cache.existenceIndexReady()) return;
        RLock lock = null;
        boolean acquired = false;
        try {
            lock = redisson.getLock(REBUILD_LOCK);
            // Redisson watchdog 自动续约，长扫描不会因固定租约到期而并发重建。
            acquired = lock.tryLock(0, TimeUnit.SECONDS);
            if (!acquired || cache.existenceIndexReady()) return;
            int count = rebuildUseCase.rebuild();
            log.info("布隆索引恢复完成 count={}", count);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            // 就绪标记保持缺失；读请求向 DB 核实，下一轮扫描继续重试。
            log.error("布隆索引重建失败，下一轮重试", e);
        } finally {
            if (acquired && lock != null) {
                try {
                    if (lock.isHeldByCurrentThread()) lock.unlock();
                } catch (RuntimeException e) {
                    log.warn("布隆重建锁释放失败，等待 watchdog 恢复", e);
                }
            }
        }
    }
}
