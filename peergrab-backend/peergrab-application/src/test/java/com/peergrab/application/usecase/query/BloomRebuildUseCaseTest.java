package com.peergrab.application.usecase.query;

import com.peergrab.domain.errand.ports.ErrandCachePort;
import com.peergrab.domain.errand.ports.ErrandQueryPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BloomRebuildUseCaseTest {

    @Test
    void readiness_is_published_only_after_all_ids_are_registered() {
        ErrandQueryPort query = mock(ErrandQueryPort.class);
        ErrandCachePort cache = mock(ErrandCachePort.class);
        when(cache.beginExistenceIndexRebuild()).thenReturn(true);
        when(cache.registerExisting(10)).thenReturn(true);
        when(cache.registerExisting(20)).thenReturn(true);
        when(cache.completeExistenceIndexRebuild()).thenReturn(true);
        when(query.scanIdsAfter(Long.MIN_VALUE, 1000)).thenReturn(List.of(10L, 20L));

        assertEquals(2, new BloomRebuildUseCase(query, cache).rebuild());
        var ordered = inOrder(cache);
        ordered.verify(cache).beginExistenceIndexRebuild();
        ordered.verify(cache).registerExisting(10);
        ordered.verify(cache).registerExisting(20);
        ordered.verify(cache).completeExistenceIndexRebuild();
    }

    @Test
    void a_failed_registration_leaves_the_index_unready() {
        ErrandQueryPort query = mock(ErrandQueryPort.class);
        ErrandCachePort cache = mock(ErrandCachePort.class);
        when(cache.beginExistenceIndexRebuild()).thenReturn(true);
        when(cache.registerExisting(10)).thenReturn(false);
        when(query.scanIdsAfter(Long.MIN_VALUE, 1000)).thenReturn(List.of(10L));

        assertThrows(IllegalStateException.class, () -> new BloomRebuildUseCase(query, cache).rebuild());
        verify(cache, never()).completeExistenceIndexRebuild();
    }
}
