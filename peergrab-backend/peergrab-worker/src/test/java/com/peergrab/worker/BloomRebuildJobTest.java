package com.peergrab.worker;

import com.peergrab.application.usecase.query.BloomRebuildUseCase;
import com.peergrab.domain.errand.ports.ErrandCachePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class BloomRebuildJobTest {

    @Test
    @DisplayName("周期校准在索引就绪时也执行全量重建，修复已就绪但漏登记的 id")
    void periodicRefreshRebuildsEvenWhenReady() throws Exception {
        BloomRebuildUseCase rebuild = mock(BloomRebuildUseCase.class);
        ErrandCachePort cache = mock(ErrandCachePort.class);
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(cache.isDegraded()).thenReturn(false);
        when(cache.existenceIndexReady()).thenReturn(true);
        when(redisson.getLock(any())).thenReturn(lock);
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(rebuild.rebuild()).thenReturn(10);

        BloomRebuildJob job = new BloomRebuildJob(rebuild, cache, redisson, true);
        job.periodicRefresh();

        verify(rebuild).rebuild();
        verify(lock).unlock();
    }

    @Test
    @DisplayName("Redis 降级时跳过周期重建")
    void degradedCacheSkipsRefresh() {
        BloomRebuildUseCase rebuild = mock(BloomRebuildUseCase.class);
        ErrandCachePort cache = mock(ErrandCachePort.class);
        RedissonClient redisson = mock(RedissonClient.class);
        when(cache.isDegraded()).thenReturn(true);

        new BloomRebuildJob(rebuild, cache, redisson, true).periodicRefresh();

        verifyNoInteractions(rebuild, redisson);
    }
}
