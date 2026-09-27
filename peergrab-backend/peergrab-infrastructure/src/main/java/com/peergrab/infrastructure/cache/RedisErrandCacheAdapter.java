package com.peergrab.infrastructure.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peergrab.domain.errand.ports.ErrandCachePort;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 任务详情缓存的 Redis 实现：默认单 key 的 Cache Aside 与互斥重建。
 * 多 key 分片和布隆提示保留为显式开启的容量实验选项。
 *
 * ── 缓存值格式 ──
 * {"exp":1755500000000,"empty":false,"data":{...详情...}}
 * exp 是逻辑过期时间。物理 TTL 比逻辑过期长一倍，所以逻辑过期后值还在，
 * 读到旧值的线程可以先返回旧值、由一个线程去异步重建（防击穿的关键）。
 *
 * ── 为什么分片 ──
 * 爆款任务的详情是热 Key，所有请求打同一个 Redis slot。写成
 * errand:detail:{id}:{generation}:{shard} 后随机读一片。全部分片写完才
 * 切换 errand:detail:{id}:active 指针；失败的半写代只会等 TTL 回收。
 *
 * ── 降级 ──
 * 读写 Redis 异常降级回源 DB；失效异常上抛，由提交后调用方隔离、消费者重试。
 * 缓存是加速手段，不能成为可用性的单点。布隆判否也要由 MySQL 核实，
 * 因为过滤器登记与数据库提交没有原子事务。
 */
@Component
@ConditionalOnProperty(name = "peergrab.cache.enabled", havingValue = "true", matchIfMissing = true)
public class RedisErrandCacheAdapter implements ErrandCachePort {

    private static final Logger log = LoggerFactory.getLogger(RedisErrandCacheAdapter.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String KEY_PREFIX = "errand:detail:";
    private static final String BLOOM_NAME = "errand:bloom";
    private static final String BLOOM_READY_KEY = "errand:bloom:ready";
    private static final String REBUILD_LOCK_PREFIX = "errand:rebuild:";
    private static final long FAILURE_COOLDOWN_MILLIS = 2_000L;

    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final int shards;
    private final long ttlSeconds;
    private final long jitterSeconds;
    private final long emptyTtlSeconds;
    private final boolean bloomEnabled;
    private final AtomicLong retryAfterMillis = new AtomicLong();
    private final AtomicBoolean probing = new AtomicBoolean();

    public RedisErrandCacheAdapter(StringRedisTemplate redis,
                                   @Lazy RedissonClient redisson,
                                   @Value("${peergrab.cache.shards:1}") int shards,
                                   @Value("${peergrab.cache.ttl-seconds:600}") long ttlSeconds,
                                   @Value("${peergrab.cache.jitter-seconds:120}") long jitterSeconds,
                                   @Value("${peergrab.cache.empty-ttl-seconds:60}") long emptyTtlSeconds,
                                   @Value("${peergrab.cache.bloom-enabled:false}") boolean bloomEnabled) {
        this.redis = redis;
        this.redisson = redisson;
        this.shards = Math.max(1, shards);
        this.ttlSeconds = ttlSeconds;
        this.jitterSeconds = jitterSeconds;
        this.emptyTtlSeconds = emptyTtlSeconds;
        this.bloomEnabled = bloomEnabled;
    }

    private String activeKey(long errandId) {
        return KEY_PREFIX + errandId + ":active";
    }

    private String singleKey(long errandId) {
        return KEY_PREFIX + errandId + ":single";
    }

    private String key(long errandId, String generation, int shard) {
        return KEY_PREFIX + errandId + ":" + generation + ":" + shard;
    }

    @Override
    public boolean isDegraded() {
        long retryAt = retryAfterMillis.get();
        return retryAt != 0 && (System.currentTimeMillis() < retryAt || probing.get());
    }

    private void markFailure() {
        retryAfterMillis.set(System.currentTimeMillis() + FAILURE_COOLDOWN_MILLIS);
    }

    private void markSuccess() {
        retryAfterMillis.set(0);
    }

    @Override
    public Optional<CachedErrand> get(long errandId) {
        if (isDegraded()) return Optional.empty();
        boolean probe = retryAfterMillis.get() != 0;
        if (probe && !probing.compareAndSet(false, true)) return Optional.empty();
        try {
            String raw;
            if (shards == 1) {
                // The current single Redis server has no node-level benefit from
                // replicas. A direct key also avoids an extra pointer GET on every hit.
                raw = redis.opsForValue().get(singleKey(errandId));
            } else {
                String generation = redis.opsForValue().get(activeKey(errandId));
                raw = generation == null ? null
                        : redis.opsForValue().get(key(errandId, generation,
                                ThreadLocalRandom.current().nextInt(shards)));
            }
            markSuccess();
            if (raw == null) {
                return Optional.empty();
            }
            return Optional.of(parse(raw));
        } catch (RuntimeException e) {
            markFailure();
            log.warn("读缓存失败，降级回源 errandId={}", errandId, e);
            return Optional.empty();
        } finally {
            if (probe) probing.set(false);
        }
    }

    @Override
    public void put(long errandId, String payloadJson) {
        long physicalTtl = ttlSeconds + ThreadLocalRandom.current().nextLong(-jitterSeconds, jitterSeconds + 1);
        long logicalExpireAt = System.currentTimeMillis() + physicalTtl * 1000 / 2;
        String value = encode(logicalExpireAt, false, parsePayload(payloadJson));
        writeAllShards(errandId, value, Duration.ofSeconds(Math.max(60, physicalTtl)));
    }

    @Override
    public void putEmpty(long errandId) {
        // 空值缓存的逻辑过期直接设为物理过期：空值没有"返回旧值"的意义
        long logicalExpireAt = System.currentTimeMillis() + emptyTtlSeconds * 1000;
        String value = encode(logicalExpireAt, true, null);
        writeAllShards(errandId, value, Duration.ofSeconds(emptyTtlSeconds));
    }

    private void writeAllShards(long errandId, String value, Duration ttl) {
        if (isDegraded()) return;
        try {
            if (shards == 1) {
                redis.opsForValue().set(singleKey(errandId), value, ttl);
            } else {
                String generation = UUID.randomUUID().toString();
                for (int i = 0; i < shards; i++) {
                    redis.opsForValue().set(key(errandId, generation, i), value, ttl);
                }
                // 指针是提交标记：半写入的代不会被任何读请求看到。
                redis.opsForValue().set(activeKey(errandId), generation, ttl);
            }
            markSuccess();
        } catch (RuntimeException e) {
            markFailure();
            log.warn("写缓存失败（不影响业务结果）errandId={}", errandId, e);
        }
    }

    @Override
    public void evict(long errandId) {
        if (isDegraded()) {
            throw new IllegalStateException("Redis 缓存处于故障冷却期，失效需重试: errandId=" + errandId);
        }
        try {
            // During an upgrade, old replicas may still read the generation pointer.
            // Delete both formats in one command; old generation keys expire by TTL.
            redis.delete(List.of(singleKey(errandId), activeKey(errandId)));
            markSuccess();
        } catch (RuntimeException e) {
            markFailure();
            throw e;
        }
    }

    /**
     * 重建权：tryLock 不等待（waitTime=0）。
     * 拿不到就立刻返回 false 让调用方返回旧值——阻塞等待会把线程耗在这里，
     * 热 Key 场景下几百个线程一起等，等于把击穿变成了线程池打满。
     */
    @Override
    public boolean tryAcquireRebuild(long errandId) {
        if (isDegraded()) return false;
        try {
            RLock lock = redisson.getLock(REBUILD_LOCK_PREFIX + errandId);
            return lock.tryLock(0, 10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException e) {
            markFailure();
            log.warn("获取重建锁失败 errandId={}", errandId, e);
            return false;
        }
    }

    @Override
    public void releaseRebuild(long errandId) {
        try {
            RLock lock = redisson.getLock(REBUILD_LOCK_PREFIX + errandId);
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (RuntimeException e) {
            markFailure();
            log.warn("释放重建锁失败 errandId={}", errandId, e);
        }
    }

    /**
     * 布隆判存在。
     *
     * Redis 与 MySQL 不共享事务，发布时布隆登记可能失败；过滤器被清空后也可能
     * 先由新任务初始化为不完整状态。因此 false 只是提示，调用方仍需向 DB 核实。
     */
    @Override
    public boolean mightExist(long errandId) {
        if (!bloomEnabled || isDegraded()) {
            return true;
        }
        try {
            RBloomFilter<Long> bloom = redisson.getBloomFilter(BLOOM_NAME);
            if (!bloom.isExists() || !Boolean.TRUE.equals(redis.hasKey(BLOOM_READY_KEY))) {
                // 部分重建中的过滤器不能用于否定判定。
                return true;
            }
            return bloom.contains(errandId);
        } catch (RuntimeException e) {
            markFailure();
            log.warn("布隆查询失败，保守放行 errandId={}", errandId, e);
            return true;
        }
    }

    @Override
    public boolean registerExisting(long errandId) {
        if (!bloomEnabled) {
            return true;
        }
        try {
            RBloomFilter<Long> bloom = redisson.getBloomFilter(BLOOM_NAME);
            if (!bloom.isExists()) {
                redis.delete(BLOOM_READY_KEY);
                bloom.tryInit(100_000L, 0.01);
            }
            bloom.add(errandId);
            return true;
        } catch (RuntimeException e) {
            markFailure();
            log.warn("布隆登记失败 errandId={}", errandId, e);
            return false;
        }
    }

    @Override
    public boolean beginExistenceIndexRebuild() {
        if (!bloomEnabled) return true;
        try {
            redis.delete(BLOOM_READY_KEY);
            return true;
        } catch (RuntimeException e) {
            markFailure();
            log.warn("撤销布隆就绪标记失败", e);
            return false;
        }
    }

    @Override
    public boolean completeExistenceIndexRebuild() {
        if (!bloomEnabled) return true;
        try {
            RBloomFilter<Long> bloom = redisson.getBloomFilter(BLOOM_NAME);
            if (!bloom.isExists()) {
                bloom.tryInit(100_000L, 0.01);
            }
            redis.opsForValue().set(BLOOM_READY_KEY, "1");
            return true;
        } catch (RuntimeException e) {
            markFailure();
            log.warn("设置布隆就绪标记失败", e);
            return false;
        }
    }

    @Override
    public boolean existenceIndexReady() {
        if (!bloomEnabled) return true;
        try {
            return Boolean.TRUE.equals(redis.hasKey(BLOOM_READY_KEY))
                    && redisson.getBloomFilter(BLOOM_NAME).isExists();
        } catch (RuntimeException e) {
            markFailure();
            return false;
        }
    }

    private CachedErrand parse(String raw) {
        try {
            JsonNode envelope = JSON.readTree(raw);
            JsonNode exp = envelope.path("exp");
            JsonNode empty = envelope.path("empty");
            JsonNode data = envelope.path("data");
            if (!exp.canConvertToLong() || !empty.isBoolean()
                    || (!empty.booleanValue() && (data.isMissingNode() || data.isNull()))) {
                throw new IllegalArgumentException("Invalid errand cache envelope");
            }
            return new CachedErrand(empty.booleanValue() ? null : data.toString(),
                    exp.longValue(), empty.booleanValue());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid errand cache envelope", e);
        }
    }

    private JsonNode parsePayload(String payloadJson) {
        try {
            return JSON.readTree(payloadJson);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid errand cache payload", e);
        }
    }

    private String encode(long exp, boolean empty, JsonNode data) {
        try {
            return JSON.writeValueAsString(new CacheEnvelope(exp, empty, data));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot encode errand cache envelope", e);
        }
    }

    private record CacheEnvelope(long exp, boolean empty, JsonNode data) {}
}
