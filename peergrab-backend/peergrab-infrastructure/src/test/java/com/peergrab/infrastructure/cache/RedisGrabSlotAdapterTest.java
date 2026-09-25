package com.peergrab.infrastructure.cache;

import com.peergrab.domain.grab.model.SlotOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class RedisGrabSlotAdapterTest {

    @Test
    void missing_lua_result_defers_to_database_cas() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") RedisScript<Long> grabScript = mock(RedisScript.class);
        @SuppressWarnings("unchecked") RedisScript<Long> rollbackScript = mock(RedisScript.class);
        RedisGrabSlotAdapter adapter = new RedisGrabSlotAdapter(redis, grabScript, rollbackScript);

        assertEquals(SlotOutcome.SLOT_MISSING, adapter.tryAcquire(10001L, 2001L, "request-1"));
    }

    @Test
    void late_slot_init_never_overwrites_or_clears_a_live_reservation() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") RedisScript<Long> grabScript = mock(RedisScript.class);
        @SuppressWarnings("unchecked") RedisScript<Long> rollbackScript = mock(RedisScript.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisGrabSlotAdapter adapter = new RedisGrabSlotAdapter(redis, grabScript, rollbackScript);

        adapter.initSlot(10001L, 1, 3600);

        verify(values).setIfAbsent("errand:slot:{10001}", "1", Duration.ofSeconds(3600));
        verify(redis, never()).delete(anyString());
        verify(redis, never()).delete(anyCollection());
    }
}
