package com.peergrab.application.usecase;

import com.peergrab.domain.errand.ports.CacheEvictDelayPort;
import com.peergrab.domain.errand.ports.ErrandCachePort;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class CacheEvictSupportTest {

    @Test
    void failed_eviction_is_counted_without_changing_committed_business_result() {
        ErrandCachePort cache = mock(ErrandCachePort.class);
        doThrow(new IllegalStateException("Redis unavailable")).when(cache).evict(42);
        CacheEvictSupport support = new CacheEvictSupport(
                cache, mock(CacheEvictDelayPort.class), 500, false);

        assertDoesNotThrow(() -> support.evictAfterCommit(42));

        assertEquals(1, support.evictFailureCount());
    }
}
