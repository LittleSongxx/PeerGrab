package com.peergrab.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;

class SnowflakeIdGeneratorClockTest {

    @Test
    @DisplayName("小幅时钟回拨等待越过而不是直接失败，ID 保持递增")
    void smallBackwardDriftIsTolerated() {
        long base = 1_700_000_000_000L;
        long back = base - 40;
        AtomicLong wall = new AtomicLong(base);
        AtomicBoolean jumped = new AtomicBoolean(false);
        LongSupplier clock = () -> {
            long value = wall.get();
            if (!jumped.get() && value == back) {
                // 第一次读到回拨值；随后时间追上，模拟 NTP 校正完成。
                jumped.set(true);
                return back;
            }
            if (jumped.get()) return base + 1;
            return value;
        };
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, 100, clock);

        long first = generator.nextId();
        wall.set(back);
        long second = generator.nextId();

        assertTrue(second > first, "回拨容忍后 ID 必须严格递增");
    }

    @Test
    @DisplayName("超过容忍窗口的时钟回拨仍直接失败")
    void largeBackwardDriftIsRejected() {
        long base = 1_700_000_000_000L;
        AtomicLong wall = new AtomicLong(base);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, 100, wall::get);

        generator.nextId();
        wall.set(base - 500);

        assertThrows(IllegalStateException.class, generator::nextId);
        assertFalse(generator.canGenerate());
    }
}
