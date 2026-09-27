package com.peergrab.application.usecase.query;

import com.peergrab.domain.errand.model.Errand;
import com.peergrab.domain.errand.model.ErrandStatus;
import com.peergrab.domain.errand.model.ErrandType;
import com.peergrab.domain.errand.ports.ErrandCachePort;
import com.peergrab.domain.errand.ports.ErrandQueryPort;
import com.peergrab.domain.errand.ports.ErrandRepository;
import com.peergrab.domain.errand.ports.SyncDiffRepository;
import com.peergrab.shared.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CacheConsistencyCheckUseCaseTest {

    @Test
    void failed_eviction_is_recorded_as_unfixed() {
        ErrandQueryPort query = mock(ErrandQueryPort.class);
        ErrandRepository repository = mock(ErrandRepository.class);
        ErrandCachePort cache = mock(ErrandCachePort.class);
        SyncDiffRepository diffs = mock(SyncDiffRepository.class);
        when(query.sampleIds(1)).thenReturn(List.of(42L));
        when(cache.get(42)).thenReturn(Optional.of(new ErrandCachePort.CachedErrand(
                "{\"status\":\"SETTLED\",\"version\":1,\"rewardCents\":500}",
                System.currentTimeMillis() + 1_000, false)));
        when(repository.findById(42)).thenReturn(Optional.of(Errand.rehydrate(
                42, 1, 1001, ErrandType.DELIVERY, "测试任务", Money.ofCents(500), 1,
                null, ErrandStatus.PUBLISHED, 0, 0, 1, null, null)));
        doThrow(new IllegalStateException("Redis unavailable")).when(cache).evict(42);

        assertEquals(1, new CacheConsistencyCheckUseCase(query, repository, cache, diffs, 1).runOnce());

        verify(diffs).record(any(Instant.class), eq(42L), eq("status"),
                eq("PUBLISHED"), eq("SETTLED"), eq(false));
    }

    @Test
    void successful_eviction_is_recorded_as_fixed() {
        ErrandQueryPort query = mock(ErrandQueryPort.class);
        ErrandRepository repository = mock(ErrandRepository.class);
        ErrandCachePort cache = mock(ErrandCachePort.class);
        SyncDiffRepository diffs = mock(SyncDiffRepository.class);
        when(query.sampleIds(1)).thenReturn(List.of(42L));
        when(cache.get(42)).thenReturn(Optional.of(new ErrandCachePort.CachedErrand(
                "{\"status\":\"SETTLED\",\"version\":1,\"rewardCents\":500}",
                System.currentTimeMillis() + 1_000, false)));
        when(repository.findById(42)).thenReturn(Optional.of(Errand.rehydrate(
                42, 1, 1001, ErrandType.DELIVERY, "测试任务", Money.ofCents(500), 1,
                null, ErrandStatus.PUBLISHED, 0, 0, 1, null, null)));

        assertEquals(1, new CacheConsistencyCheckUseCase(query, repository, cache, diffs, 1).runOnce());

        var order = inOrder(cache, diffs);
        order.verify(cache).evict(42);
        order.verify(diffs).record(any(Instant.class), eq(42L), eq("status"),
                eq("PUBLISHED"), eq("SETTLED"), eq(true));
    }
}
