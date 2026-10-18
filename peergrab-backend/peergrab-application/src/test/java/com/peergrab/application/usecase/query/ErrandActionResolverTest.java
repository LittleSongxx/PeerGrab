package com.peergrab.application.usecase.query;

import com.peergrab.domain.errand.model.ErrandStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ErrandActionResolverTest {

    private final ErrandActionResolver resolver = new ErrandActionResolver(9001L);

    @Test
    @DisplayName("仲裁员不获得抢单动作，避免成为交易当事人")
    void arbitratorNeverSeesGrab() {
        var resolved = resolver.resolve(ErrandStatus.PUBLISHED, 1001L, null, 9001L);
        assertEquals("ARBITRATOR", resolved.role());
        assertFalse(resolved.actions().contains("GRAB"));
    }

    @Test
    @DisplayName("普通跑腿仍可抢单，发单人不能抢自己的任务")
    void ordinaryRunnerSeesGrab() {
        assertTrue(resolver.resolve(ErrandStatus.PUBLISHED, 1001L, null, 2001L)
                .actions().contains("GRAB"));
        assertFalse(resolver.resolve(ErrandStatus.PUBLISHED, 1001L, null, 1001L)
                .actions().contains("GRAB"));
    }
}
