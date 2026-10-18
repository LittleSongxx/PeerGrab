package com.peergrab.presentation.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class LoginAttemptGuardTest {

    @Test
    @DisplayName("窗口内失败达到阈值后锁定，锁定到期恢复")
    void locksAfterThresholdAndRecovers() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        LoginAttemptGuard guard = new LoginAttemptGuard(true, 2, 60, 300, clock::get);

        assertTrue(guard.allow("ip|1001"));
        guard.recordFailure("ip|1001");
        assertTrue(guard.allow("ip|1001"));
        guard.recordFailure("ip|1001");
        assertFalse(guard.allow("ip|1001"), "达到阈值后应拒绝继续尝试");

        clock.addAndGet(301_000L);
        assertTrue(guard.allow("ip|1001"), "锁定到期后允许重新尝试");
    }

    @Test
    @DisplayName("成功登录清零失败计数；不同 key 互不影响")
    void successResetsAndKeysAreIndependent() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        LoginAttemptGuard guard = new LoginAttemptGuard(true, 2, 60, 300, clock::get);

        guard.recordFailure("ip-a|1001");
        guard.recordSuccess("ip-a|1001");
        guard.recordFailure("ip-a|1001");
        assertTrue(guard.allow("ip-a|1001"), "成功登录后重新计数");

        guard.recordFailure("ip-b|1001");
        guard.recordFailure("ip-b|1001");
        assertFalse(guard.allow("ip-b|1001"));
        assertTrue(guard.allow("ip-c|1001"), "其他来源不应被牵连");
    }

    @Test
    @DisplayName("过期且未锁定的 key 会被定期清理，避免内存无限增长")
    void staleKeysArePruned() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        LoginAttemptGuard guard = new LoginAttemptGuard(true, 5, 60, 300, clock::get);
        for (int i = 0; i < 50; i++) {
            assertTrue(guard.allow("ip-" + i + "|1001"));
        }
        assertEquals(50, guard.trackedKeys());

        clock.addAndGet(120_000L);
        assertTrue(guard.allow("fresh|1001"));

        assertTrue(guard.trackedKeys() <= 1, "过期窗口的 key 应被清理，实际=" + guard.trackedKeys());
    }

    @Test
    @DisplayName("关闭限速时始终放行")
    void disabledAlwaysAllows() {
        LoginAttemptGuard guard = new LoginAttemptGuard(false, 1, 60, 300, () -> 0L);
        guard.recordFailure("k");
        assertTrue(guard.allow("k"));
    }
}
