package com.peergrab.domain;

import com.peergrab.domain.errand.model.ErrandStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ErrandStatusMachineTest {

    @Test
    @DisplayName("只有 PUBLISHED 状态可被抢单")
    void onlyPublishedIsGrabbable() {
        assertTrue(ErrandStatus.PUBLISHED.grabbable());
        for (ErrandStatus s : ErrandStatus.values()) {
            if (s != ErrandStatus.PUBLISHED) {
                assertFalse(s.grabbable(), s + " 不应该可抢");
            }
        }
    }

    @Test
    @DisplayName("LOCKED -> LOCKED 是合法自环（超时流转给下一位候选人）")
    void lockedToLockedIsLegalSelfLoop() {
        assertTrue(ErrandStatus.LOCKED.canTransitTo(ErrandStatus.LOCKED));
        // 流转失败回退到重新开放也合法
        assertTrue(ErrandStatus.LOCKED.canTransitTo(ErrandStatus.PUBLISHED));
    }

    @Test
    @DisplayName("跳跃流转必须被拒绝")
    void illegalJumpsAreRejected() {
        assertFalse(ErrandStatus.PUBLISHED.canTransitTo(ErrandStatus.SETTLED));
        assertFalse(ErrandStatus.DRAFT.canTransitTo(ErrandStatus.LOCKED));
        assertFalse(ErrandStatus.LOCKED.canTransitTo(ErrandStatus.DELIVERED));
    }

    @Test
    @DisplayName("终态没有任何出边")
    void terminalStatesHaveNoOutgoing() {
        assertTrue(ErrandStatus.SETTLED.allowedTargets().isEmpty());
        assertTrue(ErrandStatus.REFUNDED.allowedTargets().isEmpty());
        assertTrue(ErrandStatus.CANCELLED.allowedTargets().isEmpty());
        // 已有人接单后不能由发单人单方面取消：状态机不再声明 CANCELLED 出边。
        assertFalse(ErrandStatus.LOCKED.canTransitTo(ErrandStatus.CANCELLED));
        assertFalse(ErrandStatus.ACCEPTED.canTransitTo(ErrandStatus.CANCELLED));
    }
}
