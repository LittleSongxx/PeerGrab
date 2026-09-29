package com.peergrab.application.usecase.query;

import com.peergrab.domain.errand.model.Errand;
import com.peergrab.domain.errand.model.ErrandStatus;
import com.peergrab.domain.errand.model.ErrandType;
import com.peergrab.domain.errand.ports.ErrandCachePort;
import com.peergrab.domain.errand.ports.ErrandRepository;
import com.peergrab.shared.BizException;
import com.peergrab.shared.Money;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class ErrandDetailPrewarmUseCaseTest {

    private static Errand errand(long id) {
        return Errand.rehydrate(id, 1, 1001, ErrandType.DELIVERY, "预热任务-" + id,
                Money.ofCents(100), 1, null, ErrandStatus.PUBLISHED,
                0, 0, 7, null, null);
    }

    @Test
    void deduplicates_ids_reads_in_bulk_and_writes_one_batch() {
        ErrandRepository repository = mock(ErrandRepository.class);
        ErrandCachePort cache = mock(ErrandCachePort.class);
        when(cache.isEnabled()).thenReturn(true);
        when(repository.findByIds(List.of(42L, 43L))).thenReturn(List.of(errand(42), errand(43)));

        AtomicReference<Map<Long, String>> written = new AtomicReference<>();
        doAnswer(inv -> {
            written.set(inv.getArgument(0));
            return null;
        }).when(cache).putAll(anyMap());

        var result = new ErrandDetailPrewarmUseCase(repository, cache)
                .prewarm(List.of(42L, 42L, 43L));

        assertEquals(3, result.requested());
        assertEquals(2, result.distinct());
        assertEquals(2, result.found());
        assertEquals(2, result.written());
        assertEquals(0, result.missing());
        assertTrue(result.cacheHealthy());
        assertEquals(List.of(42L, 43L), written.get().keySet().stream().toList());
        assertTrue(written.get().get(42L).contains("\"id\":\"42\""));
        verify(repository).findByIds(List.of(42L, 43L));
        verify(cache).putAll(anyMap());
    }

    @Test
    void rejects_unbounded_or_invalid_requests_before_touching_storage() {
        ErrandRepository repository = mock(ErrandRepository.class);
        ErrandCachePort cache = mock(ErrandCachePort.class);
        when(cache.isEnabled()).thenReturn(true);
        var useCase = new ErrandDetailPrewarmUseCase(repository, cache);

        assertThrows(BizException.class,
                () -> useCase.prewarm(java.util.Collections.nCopies(1001, 1L)));
        assertThrows(BizException.class, () -> useCase.prewarm(List.of(0L)));
        verifyNoInteractions(repository, cache);
    }

    @Test
    void degraded_redis_skips_database_and_reports_no_write() {
        ErrandRepository repository = mock(ErrandRepository.class);
        ErrandCachePort cache = mock(ErrandCachePort.class);
        when(cache.isEnabled()).thenReturn(true);
        when(cache.isDegraded()).thenReturn(true);

        var result = new ErrandDetailPrewarmUseCase(repository, cache)
                .prewarm(List.of(42L, 43L));

        assertEquals(2, result.distinct());
        assertEquals(0, result.found());
        assertEquals(0, result.written());
        assertFalse(result.cacheHealthy());
        verifyNoInteractions(repository);
        verify(cache, never()).putAll(anyMap());
    }

    @Test
    void chunks_large_requests_to_bound_sql_parameter_count() {
        ErrandRepository repository = mock(ErrandRepository.class);
        ErrandCachePort cache = mock(ErrandCachePort.class);
        when(cache.isEnabled()).thenReturn(true);
        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 251).boxed().toList();
        when(repository.findByIds(anyList())).thenReturn(List.of());

        var result = new ErrandDetailPrewarmUseCase(repository, cache).prewarm(ids);

        assertEquals(251, result.distinct());
        var chunks = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(repository, times(2)).findByIds(chunks.capture());
        assertEquals(250, chunks.getAllValues().get(0).size());
        assertEquals(1, chunks.getAllValues().get(1).size());
    }
}
