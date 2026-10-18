package com.peergrab.domain;

import com.peergrab.domain.errand.model.Errand;
import com.peergrab.domain.errand.model.ErrandStatus;
import com.peergrab.domain.errand.model.ErrandType;
import com.peergrab.shared.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** 连续递补计数与回退归零：达到上限后的任务在重新开放时应重新获得候选递补机会。 */
class ErrandTransferCountTest {

    private static Errand locked(int transferCount) {
        return Errand.rehydrate(1L, 1L, 1001L, ErrandType.DELIVERY, "测试任务",
                Money.ofCents(1000), 1, 2001L, ErrandStatus.LOCKED, 1, 3, 7L,
                Instant.parse("2026-10-01T00:00:00Z"), null, null, null, transferCount);
    }

    @Test
    @DisplayName("递补一次计数 +1，回退重新开放归零")
    void transferIncrementsAndRevertResets() {
        Errand errand = locked(0);
        errand.transferToNextRunner(2002L, errand.version(), Instant.parse("2026-10-01T00:05:00Z"));
        assertEquals(1, errand.transferCount());
        assertEquals(4, errand.round());

        errand.revertToPublished(errand.version());
        assertEquals(0, errand.transferCount(), "回退后应重新获得完整的候选递补预算");
        assertEquals(5, errand.round(), "round 仍单调递增，用于消息幂等，不随预算重置");
        assertEquals(ErrandStatus.PUBLISHED, errand.status());
    }

    @Test
    @DisplayName("旧的重建重载保持 transferCount=0 兼容")
    void legacyRehydrateDefaultsToZero() {
        Errand errand = Errand.rehydrate(1L, 1L, 1001L, ErrandType.DELIVERY, "测试任务",
                Money.ofCents(1000), 1, 2001L, ErrandStatus.LOCKED, 1, 0, 2L,
                Instant.parse("2026-10-01T00:00:00Z"));
        assertEquals(0, errand.transferCount());
    }
}
