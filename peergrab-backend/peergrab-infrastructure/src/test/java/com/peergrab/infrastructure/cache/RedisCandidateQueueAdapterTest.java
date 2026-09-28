package com.peergrab.infrastructure.cache;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class RedisCandidateQueueAdapterTest {

    @Test
    void offer_returns_atomic_lua_size_without_separate_zcard_reads() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisCandidateQueueAdapter adapter = new RedisCandidateQueueAdapter(redis, 86400, 30);
        List<String> keys = List.of("errand:candidates:{10001}",
                "errand:candidates:{10001}:claims", "errand:candidates:{10001}:scores",
                "errand:candidates:{10001}:tokens");
        when(redis.execute(any(RedisScript.class), eq(keys), eq("1234.0"), eq("2001"), eq("86400")))
                .thenReturn(3L);

        assertEquals(3L, adapter.offer(10001L, 2001L, 1234.0));

        verify(redis).execute(any(RedisScript.class), eq(keys), eq("1234.0"), eq("2001"), eq("86400"));
        verifyNoMoreInteractions(redis);
    }

    @Test
    void offer_rejects_missing_lua_result_instead_of_reporting_a_false_rank() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisCandidateQueueAdapter adapter = new RedisCandidateQueueAdapter(redis, 86400, 30);

        assertThrows(IllegalStateException.class, () -> adapter.offer(10001L, 2001L, 1234.0));
    }
}
