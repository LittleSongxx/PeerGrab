package com.peergrab.infrastructure.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peergrab.domain.errand.ports.ErrandCachePort;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ConcurrentHashMap;

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
    /**
     * Publish a complete generation atomically. The previous implementation
     * sent one SET per shard and then switched the active pointer. Under a
     * cold fill that multiplied round trips (and a mid-flight connection
     * failure could expose a partial generation). One Lua command keeps the
     * pointer switch and all shard writes in the same Redis atomic section.
     */
    private static final DefaultRedisScript<Long> WRITE_GENERATION_SCRIPT =
            new DefaultRedisScript<>("""
                    for i = 1, #KEYS - 1 do
                      redis.call('SET', KEYS[i], ARGV[1], 'PX', ARGV[2])
                    end
                    redis.call('SET', KEYS[#KEYS], ARGV[3], 'PX', ARGV[2])
                    return 1
                    """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE_REBUILD_SCRIPT =
            new DefaultRedisScript<>("""
                    if redis.call('GET', KEYS[1]) == ARGV[1] then
                      return redis.call('DEL', KEYS[1])
                    end
                    return 0
                    """, Long.class);

    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final int shards;
    private final long ttlSeconds;
    private final long jitterSeconds;
    private final long emptyTtlSeconds;
    private final boolean bloomEnabled;
    private final long localFallbackTtlMillis;
    private final int localFallbackMaxEntries;
    private final AtomicLong retryAfterMillis = new AtomicLong();
    private final AtomicBoolean probing = new AtomicBoolean();
    /** SET NX tokens let the one-key path acquire/release in one round trip each. */
    private final ConcurrentHashMap<Long, String> rebuildTokens = new ConcurrentHashMap<>();
    /**
     * Tiny process-local safety net used only while Redis is degraded. It is
     * deliberately short-lived and bounded: it absorbs the Redis timeout
     * without turning the local copy into a second authoritative cache.
     */
    private final ConcurrentHashMap<Long, LocalFallback> localFallbacks = new ConcurrentHashMap<>();

    @Autowired
    public RedisErrandCacheAdapter(StringRedisTemplate redis,
                                   @Lazy RedissonClient redisson,
                                   @Value("${peergrab.cache.shards:1}") int shards,
                                   @Value("${peergrab.cache.ttl-seconds:600}") long ttlSeconds,
                                   @Value("${peergrab.cache.jitter-seconds:120}") long jitterSeconds,
                                   @Value("${peergrab.cache.empty-ttl-seconds:60}") long emptyTtlSeconds,
                                   @Value("${peergrab.cache.bloom-enabled:false}") boolean bloomEnabled,
                                   @Value("${peergrab.cache.local-fallback-ttl-millis:1000}") long localFallbackTtlMillis,
                                   @Value("${peergrab.cache.local-fallback-max-entries:10000}") int localFallbackMaxEntries) {
        this(redis, redisson, shards, ttlSeconds, jitterSeconds, emptyTtlSeconds, bloomEnabled,
                localFallbackTtlMillis, localFallbackMaxEntries, true);
    }

    /** Lightweight constructor retained for infrastructure unit tests. */
    public RedisErrandCacheAdapter(StringRedisTemplate redis,
                                   @Lazy RedissonClient redisson,
                                   int shards, long ttlSeconds, long jitterSeconds,
                                   long emptyTtlSeconds, boolean bloomEnabled) {
        this(redis, redisson, shards, ttlSeconds, jitterSeconds, emptyTtlSeconds, bloomEnabled,
                1_000L, 10_000, false);
    }

    private RedisErrandCacheAdapter(StringRedisTemplate redis,
                                    RedissonClient redisson,
                                    int shards, long ttlSeconds, long jitterSeconds,
                                    long emptyTtlSeconds, boolean bloomEnabled,
                                    long localFallbackTtlMillis, int localFallbackMaxEntries,
                                    boolean ignored) {
        this.redis = redis;
        this.redisson = redisson;
        this.shards = Math.max(1, shards);
        this.ttlSeconds = ttlSeconds;
        this.jitterSeconds = jitterSeconds;
        this.emptyTtlSeconds = emptyTtlSeconds;
        this.bloomEnabled = bloomEnabled;
        this.localFallbackTtlMillis = Math.max(0, localFallbackTtlMillis);
        this.localFallbackMaxEntries = Math.max(0, localFallbackMaxEntries);
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
        if (isDegraded()) return localFallback(errandId);
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
            CachedErrand cached = parse(raw);
            rememberLocal(errandId, cached);
            return Optional.of(cached);
        } catch (RuntimeException e) {
            markFailure();
            log.warn("读缓存失败，降级回源 errandId={}", errandId, e);
            return localFallback(errandId);
        } finally {
            if (probe) probing.set(false);
        }
    }

    @Override
    public void put(long errandId, String payloadJson) {
        PreparedValue prepared = prepareValue(payloadJson);
        writeAllShards(errandId, prepared.value(), prepared.ttl());
    }

    /**
     * 预热专用批量写入。单 Redis 实例下把多个 SETEX 放入一个 pipeline，
     * 把每个任务一次写入的网络往返从 N 次降为 1 次；每个 key 仍有独立的
     * 逻辑过期和物理 TTL，且失败时整个预热只影响加速，不影响业务读路径。
     * 多分片仍走逐任务的原子代切换，避免破坏分片发布语义。
     */
    @Override
    public void putAll(Map<Long, String> payloads) {
        if (payloads == null || payloads.isEmpty() || isDegraded()) return;
        if (shards != 1 || redis.getConnectionFactory() == null) {
            payloads.forEach(this::put);
            return;
        }

        Map<Long, PreparedValue> prepared = new java.util.LinkedHashMap<>();
        payloads.forEach((id, json) -> {
            if (id == null || id <= 0 || json == null) {
                throw new IllegalArgumentException("Invalid detail cache prewarm entry");
            }
            prepared.put(id, prepareValue(json));
        });
        try {
            redis.executePipelined((RedisCallback<Object>) connection -> {
                prepared.forEach((id, value) -> connection.setEx(
                        singleKey(id).getBytes(StandardCharsets.UTF_8),
                        value.ttl().toSeconds(),
                        value.value().getBytes(StandardCharsets.UTF_8)));
                return null;
            });
            prepared.forEach((id, value) -> rememberLocal(id, parse(value.value())));
            markSuccess();
        } catch (RuntimeException e) {
            markFailure();
            log.warn("批量预热详情缓存失败（不影响业务结果），entries={}", prepared.size(), e);
        }
    }

    private PreparedValue prepareValue(String payloadJson) {
        long physicalTtl = ttlSeconds + ThreadLocalRandom.current().nextLong(-jitterSeconds, jitterSeconds + 1);
        long logicalExpireAt = System.currentTimeMillis() + physicalTtl * 1000 / 2;
        String value = encode(logicalExpireAt, false, parsePayload(payloadJson));
        return new PreparedValue(value, Duration.ofSeconds(Math.max(60, physicalTtl)));
    }

    private record PreparedValue(String value, Duration ttl) {}

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
                List<String> keys = new java.util.ArrayList<>(shards + 1);
                for (int i = 0; i < shards; i++) {
                    keys.add(key(errandId, generation, i));
                }
                keys.add(activeKey(errandId));
                if (redis.getConnectionFactory() != null) {
                    // One atomic EVAL replaces 2*shards+1 sequential round
                    // trips and preserves the old generation on script error.
                    Long result = redis.execute(WRITE_GENERATION_SCRIPT, keys, value,
                            String.valueOf(Math.max(1, ttl.toMillis())), generation);
                    if (result == null || result != 1L) {
                        throw new IllegalStateException("Redis generation publish returned no acknowledgement");
                    }
                } else {
                    // Lightweight unit-test doubles do not expose a connection
                    // factory. Keep their old observable behavior without
                    // weakening the production path above.
                    for (int i = 0; i < shards; i++) {
                        redis.opsForValue().set(keys.get(i), value, ttl);
                    }
                    redis.opsForValue().set(keys.get(shards), generation, ttl);
                }
            }
            rememberLocal(errandId, parse(value));
            markSuccess();
        } catch (RuntimeException e) {
            markFailure();
            log.warn("写缓存失败（不影响业务结果）errandId={}", errandId, e);
        }
    }

    @Override
    public void evict(long errandId) {
        // A committed write must never leave a process-local stale copy even
        // when Redis is already in its failure cooldown.
        localFallbacks.remove(errandId);
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

    private void rememberLocal(long errandId, CachedErrand value) {
        if (localFallbackTtlMillis <= 0 || localFallbackMaxEntries <= 0 || value.isEmpty()) return;
        if (!localFallbacks.containsKey(errandId) && localFallbacks.size() >= localFallbackMaxEntries) {
            var iterator = localFallbacks.keySet().iterator();
            if (iterator.hasNext()) localFallbacks.remove(iterator.next());
        }
        localFallbacks.put(errandId, new LocalFallback(value,
                System.currentTimeMillis() + localFallbackTtlMillis));
    }

    private Optional<CachedErrand> localFallback(long errandId) {
        LocalFallback fallback = localFallbacks.get(errandId);
        if (fallback == null || fallback.expiresAtMillis() <= System.currentTimeMillis()) {
            if (fallback != null) localFallbacks.remove(errandId, fallback);
            return Optional.empty();
        }
        return Optional.of(fallback.value());
    }

    private record LocalFallback(CachedErrand value, long expiresAtMillis) {}

    /**
     * 重建权：用 Redis SET NX PX token 取代 Redisson RLock。
     *
     * A cold S3 fill otherwise pays the Redisson lock protocol before the DB
     * read and again on release. The token is checked by a Lua unlock, so a
     * late releaser cannot delete a newer owner's lock; the 10-second lease
     * still bounds abandoned locks. trySetIfAbsent never waits, preserving the
     * stale-value/non-blocking hot-key behavior.
     */
    @Override
    public boolean tryAcquireRebuild(long errandId) {
        if (isDegraded()) return false;
        try {
            String token = UUID.randomUUID().toString();
            boolean acquired = Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(
                    REBUILD_LOCK_PREFIX + errandId, token, Duration.ofSeconds(10)));
            if (acquired) rebuildTokens.put(errandId, token);
            return acquired;
        } catch (RuntimeException e) {
            markFailure();
            log.warn("获取重建锁失败 errandId={}", errandId, e);
            return false;
        }
    }

    @Override
    public void releaseRebuild(long errandId) {
        String token = rebuildTokens.remove(errandId);
        if (token == null || isDegraded()) return;
        try {
            Long result = redis.execute(RELEASE_REBUILD_SCRIPT,
                    List.of(REBUILD_LOCK_PREFIX + errandId), token);
            if (result == null) throw new IllegalStateException("Redis unlock returned no acknowledgement");
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
