package com.peergrab.infrastructure.cache;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

class RedisErrandCacheAdapterTest {

    @Test
    void partial_shard_write_never_publishes_new_generation() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        Map<String, String> stored = new HashMap<>();
        AtomicBoolean injectFailure = new AtomicBoolean();
        AtomicInteger writes = new AtomicInteger();
        when(values.get(anyString())).thenAnswer(inv -> stored.get(inv.getArgument(0)));
        doAnswer(inv -> {
            String key = inv.getArgument(0);
            if (injectFailure.get() && !key.endsWith(":active") && writes.incrementAndGet() == 2) {
                throw new IllegalStateException("second shard write failed");
            }
            stored.put(key, inv.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));

        RedisErrandCacheAdapter writer = new RedisErrandCacheAdapter(
                redis, mock(RedissonClient.class), 4, 600, 0, 60, false);
        writer.put(42, "{\"version\":1}");
        String oldGeneration = stored.get("errand:detail:42:active");

        injectFailure.set(true);
        writer.put(42, "{\"version\":2}");

        assertEquals(oldGeneration, stored.get("errand:detail:42:active"));
        RedisErrandCacheAdapter reader = new RedisErrandCacheAdapter(
                redis, mock(RedissonClient.class), 4, 600, 0, 60, false);
        for (int i = 0; i < 20; i++) {
            assertEquals("{\"version\":1}", reader.get(42).orElseThrow().payloadJson());
        }
    }

    @Test
    void failed_eviction_is_visible_to_caller() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.delete(anyCollection())).thenThrow(new IllegalStateException("Redis unavailable"));
        RedisErrandCacheAdapter adapter = new RedisErrandCacheAdapter(
                redis, mock(RedissonClient.class), 4, 600, 0, 60, false);

        assertThrows(IllegalStateException.class, () -> adapter.evict(42));
        assertTrue(adapter.isDegraded());
    }

    @Test
    void one_shard_uses_one_direct_value_lookup() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, String> values =
                mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        java.util.Map<String, String> stored = new java.util.HashMap<>();
        doAnswer(inv -> {
            stored.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        when(values.get(anyString())).thenAnswer(inv -> stored.get(inv.getArgument(0)));

        RedisErrandCacheAdapter cache = new RedisErrandCacheAdapter(
                redis, mock(RedissonClient.class), 1, 600, 0, 60, false);
        cache.put(42, "{\"version\":1}");
        assertEquals("{\"version\":1}", cache.get(42).orElseThrow().payloadJson());
        verify(values).get("errand:detail:42:single");
        verify(values, never()).get("errand:detail:42:active");
    }

    @Test
    void sharded_fill_publishes_one_atomic_generation_script() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.getConnectionFactory()).thenReturn(mock(RedisConnectionFactory.class));
        doReturn(1L).when(redis).execute(any(RedisScript.class), anyList(), any(), any(), any());

        RedisErrandCacheAdapter cache = new RedisErrandCacheAdapter(
                redis, mock(RedissonClient.class), 4, 600, 0, 60, false);
        cache.put(42, "{\"version\":1}");

        ArgumentCaptor<java.util.List<String>> keys = ArgumentCaptor.forClass(java.util.List.class);
        verify(redis).execute(any(RedisScript.class), keys.capture(), any(), any(), any());
        assertEquals(5, keys.getValue().size());
        assertTrue(keys.getValue().get(0).matches("errand:detail:42:[0-9a-f-]+:0"));
        assertEquals("errand:detail:42:active", keys.getValue().get(4));
    }

    @Test
    void rebuild_lock_uses_token_set_nx_and_compare_delete() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(eq("errand:rebuild:42"), anyString(), any(Duration.class)))
                .thenReturn(true);
        doReturn(1L).when(redis).execute(any(RedisScript.class), anyList(), any());

        RedisErrandCacheAdapter cache = new RedisErrandCacheAdapter(
                redis, mock(RedissonClient.class), 1, 600, 0, 60, false);
        assertTrue(cache.tryAcquireRebuild(42));
        cache.releaseRebuild(42);

        verify(values).setIfAbsent(eq("errand:rebuild:42"), anyString(), eq(Duration.ofSeconds(10)));
        verify(redis).execute(any(RedisScript.class), eq(java.util.List.of("errand:rebuild:42")), any());
    }

    @Test
    void redis_failure_serves_only_a_short_local_copy_and_eviction_clears_it() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        String raw = "{\"exp\":9999999999999,\"empty\":false,\"data\":{\"id\":\"42\"}}";
        when(values.get("errand:detail:42:single"))
                .thenReturn(raw)
                .thenThrow(new IllegalStateException("Redis unavailable"));
        when(redis.delete(anyCollection())).thenThrow(new IllegalStateException("Redis unavailable"));

        RedisErrandCacheAdapter cache = new RedisErrandCacheAdapter(
                redis, mock(RedissonClient.class), 1, 600, 0, 60, false);
        assertTrue(cache.get(42).isPresent());
        assertEquals("{\"id\":\"42\"}", cache.get(42).orElseThrow().payloadJson());
        assertTrue(cache.isDegraded());

        assertThrows(IllegalStateException.class, () -> cache.evict(42));
        assertTrue(cache.get(42).isEmpty(), "committed writes must clear the local degraded copy");
    }

    @Test
    void reads_legacy_envelope_independent_of_field_order_and_whitespace() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("errand:detail:42:single")).thenReturn("""
                { "data": {"title":"a } and \\\" quote", "id":9223372036854775807},
                  "empty" : false, "exp" : 1755500000000 }
                """);

        RedisErrandCacheAdapter cache = new RedisErrandCacheAdapter(
                redis, mock(RedissonClient.class), 1, 600, 0, 60, false);
        var value = cache.get(42).orElseThrow();
        assertEquals(1755500000000L, value.logicalExpireAt());
        assertEquals("{\"title\":\"a } and \\\" quote\",\"id\":9223372036854775807}",
                value.payloadJson());
    }

    @Test
    void bulk_prewarm_uses_one_redis_pipeline_for_single_shard() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(redis.getConnectionFactory()).thenReturn(factory);
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            RedisCallback<Object> callback = (RedisCallback<Object>) inv.getArgument(0);
            callback.doInRedis(connection);
            return List.of();
        }).when(redis).executePipelined(any(RedisCallback.class));

        RedisErrandCacheAdapter cache = new RedisErrandCacheAdapter(
                redis, mock(RedissonClient.class), 1, 600, 0, 60, false);
        cache.putAll(Map.of(42L, "{\"version\":1}", 43L, "{\"version\":2}"));

        verify(redis).executePipelined(any(RedisCallback.class));
        verify(connection, times(2)).setEx(any(byte[].class), anyLong(), any(byte[].class));
        verify(redis, never()).opsForValue();
    }
}
