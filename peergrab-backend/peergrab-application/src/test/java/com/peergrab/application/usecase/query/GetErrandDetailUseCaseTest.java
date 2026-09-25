package com.peergrab.application.usecase.query;

import com.peergrab.domain.errand.model.Errand;
import com.peergrab.domain.errand.model.ErrandStatus;
import com.peergrab.domain.errand.model.ErrandType;
import com.peergrab.domain.errand.ports.ErrandCachePort;
import com.peergrab.domain.errand.ports.ErrandRepository;
import com.peergrab.shared.Money;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class GetErrandDetailUseCaseTest {

    private static Errand errand(long id) {
        return Errand.rehydrate(id, 1, 1001, ErrandType.DELIVERY, "测试任务",
                Money.ofCents(500), 1, null, ErrandStatus.PUBLISHED,
                0, 0, 1, null, null);
    }

    @Test
    void bloom_negative_never_hides_a_real_task() {
        ErrandRepository repository = mock(ErrandRepository.class);
        ErrandCachePort cache = mock(ErrandCachePort.class);
        when(cache.get(42)).thenReturn(Optional.empty());
        when(cache.mightExist(42)).thenReturn(false);
        when(cache.tryAcquireRebuild(42)).thenReturn(true);
        when(repository.findById(42)).thenReturn(Optional.of(errand(42)));

        var detail = new GetErrandDetailUseCase(repository, cache).detailJson(42);

        assertTrue(detail.isPresent());
        assertTrue(detail.get().contains("\"id\":\"42\""));
        verify(cache).registerExisting(42);
        verify(repository).findById(42);
    }

    @Test
    void cold_miss_waits_for_the_first_loader() throws Exception {
        ErrandRepository repository = mock(ErrandRepository.class);
        ErrandCachePort cache = mock(ErrandCachePort.class);
        AtomicReference<ErrandCachePort.CachedErrand> value = new AtomicReference<>();
        AtomicBoolean locked = new AtomicBoolean();
        CountDownLatch dbEntered = new CountDownLatch(1);
        CountDownLatch releaseDb = new CountDownLatch(1);
        when(cache.get(42)).thenAnswer(inv -> Optional.ofNullable(value.get()));
        when(cache.mightExist(42)).thenReturn(true);
        when(cache.tryAcquireRebuild(42)).thenAnswer(inv -> locked.compareAndSet(false, true));
        doAnswer(inv -> {
            value.set(new ErrandCachePort.CachedErrand(inv.getArgument(1),
                    System.currentTimeMillis() + 60_000, false));
            return null;
        }).when(cache).put(eq(42L), anyString());
        doAnswer(inv -> { locked.set(false); return null; }).when(cache).releaseRebuild(42);
        when(repository.findById(42)).thenAnswer(inv -> {
            dbEntered.countDown();
            assertTrue(releaseDb.await(2, TimeUnit.SECONDS));
            return Optional.of(errand(42));
        });
        GetErrandDetailUseCase useCase = new GetErrandDetailUseCase(repository, cache);

        CompletableFuture<Optional<String>> first = CompletableFuture.supplyAsync(() -> useCase.detailJson(42));
        assertTrue(dbEntered.await(2, TimeUnit.SECONDS));
        CompletableFuture<Optional<String>> second = CompletableFuture.supplyAsync(() -> useCase.detailJson(42));
        Thread.sleep(10);
        releaseDb.countDown();

        assertEquals(first.get(2, TimeUnit.SECONDS), second.get(2, TimeUnit.SECONDS));
        verify(repository, times(1)).findById(42);
    }
}
