package com.peergrab.application.usecase;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WalletDeadlockRetryTest {

    @Test
    void retriesOnlyTransientLockFailuresAndStopsAfterThreeAttempts() {
        AtomicInteger attempts = new AtomicInteger();
        String result = WalletDeadlockRetry.execute(() -> {
            if (attempts.incrementAndGet() < 3) throw new CannotAcquireLockException("deadlock");
            return "committed";
        });
        assertEquals("committed", result);
        assertEquals(3, attempts.get());

        attempts.set(0);
        assertThrows(CannotAcquireLockException.class, () -> WalletDeadlockRetry.execute(() -> {
            attempts.incrementAndGet();
            throw new CannotAcquireLockException("deadlock");
        }));
        assertEquals(3, attempts.get());
    }

    @Test
    void doesNotRetryUnknownOutcomeOrApplicationFailure() {
        AtomicInteger attempts = new AtomicInteger();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> WalletDeadlockRetry.execute(() -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("unknown commit outcome");
                }));
        assertEquals("unknown commit outcome", failure.getMessage());
        assertEquals(1, attempts.get());
    }
}
