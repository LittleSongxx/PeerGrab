package com.peergrab.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class SnowflakeIdGeneratorLeaseTest {

    @Test
    void refusesIdsAtPersistedTimeCeiling() {
        var generator = new SnowflakeIdGenerator(1);
        generator.extendLease(System.nanoTime() + 1_000_000_000L, System.currentTimeMillis() - 1);

        assertThrows(IllegalStateException.class, generator::nextId);
        assertThrows(IllegalStateException.class, generator::nextId);
    }

    @Test
    void refusesIdsAfterLocalDeadline() {
        var generator = new SnowflakeIdGenerator(1);
        generator.extendLease(System.nanoTime() - 1, System.currentTimeMillis() + 60_000);

        assertThrows(IllegalStateException.class, generator::nextId);
    }
}
