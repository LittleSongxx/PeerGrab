package com.peergrab.application.usecase;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/** Replays an entire idempotent fund operation only after the failed DB transaction has rolled back. */
final class WalletDeadlockRetry {

    private static final int MAX_ATTEMPTS = 3;

    private WalletDeadlockRetry() {}

    static <T> T execute(Supplier<T> operation) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("deadlock retry requires a new transaction per attempt");
        }
        for (int attempt = 1; ; attempt++) {
            try {
                return operation.get();
            } catch (PessimisticLockingFailureException e) {
                if (attempt >= MAX_ATTEMPTS) throw e;
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextLong(10L * attempt, 30L * attempt));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
