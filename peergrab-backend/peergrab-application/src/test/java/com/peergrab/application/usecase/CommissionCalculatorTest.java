package com.peergrab.application.usecase;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommissionCalculatorTest {

    @Test
    void decimalRateRoundsDownExactlyOnce() {
        // 0.29 在 double 下是 0.289999...；Math.floor(100*0.29) 会得到 28 而不是 29。
        assertEquals(29, CommissionCalculator.commissionCents(100, 0.29));
        assertEquals(71, 100 - CommissionCalculator.commissionCents(100, 0.29));
        assertEquals(0, CommissionCalculator.commissionCents(0, 0.29));
        assertEquals(0, CommissionCalculator.commissionCents(3, 0.05));
        assertEquals(5, CommissionCalculator.commissionCents(100, 0.05));
    }

    @Test
    void invalidRateFailsFast() {
        assertThrows(IllegalArgumentException.class, () -> CommissionCalculator.commissionCents(100, 5));
        assertThrows(IllegalArgumentException.class, () -> CommissionCalculator.commissionCents(100, -0.1));
        assertThrows(IllegalArgumentException.class, () -> CommissionCalculator.commissionCents(100, 1));
    }
}
