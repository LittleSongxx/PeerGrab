package com.peergrab.application.usecase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** A realtime hint must describe a committed state and must never decide business success. */
final class AfterCommitRealtime {

    private static final Logger log = LoggerFactory.getLogger(AfterCommitRealtime.class);

    private AfterCommitRealtime() {}

    static void send(Runnable action) {
        Runnable safe = () -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                log.warn("实时通知失败，客户端将从数据库同步状态", e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safe.run();
                }
            });
        } else {
            safe.run();
        }
    }
}
