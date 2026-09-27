package com.peergrab.infrastructure.cache;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

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
}
