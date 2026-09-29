package com.peergrab.bench;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AutoSettleTimelineProbeTest {
    @Test
    void modesStayDistinctAndInvalidCountsAreRejected() {
        assertEquals("scan", AutoSettleTimelineProbe.parse(
                new String[] {"scan", "100", "30", "60"}).mode());
        assertEquals("combined", AutoSettleTimelineProbe.parse(
                new String[] {"combined", "100", "30", "60", "rmqbroker:8081"}).mode());
        assertThrows(IllegalArgumentException.class, () -> AutoSettleTimelineProbe.parse(
                new String[] {"combined", "100", "30", "60"}));
        assertThrows(IllegalArgumentException.class, () -> AutoSettleTimelineProbe.parse(
                new String[] {"scan", "2001", "30", "60"}));
        assertThrows(IllegalArgumentException.class, () -> AutoSettleTimelineProbe.parse(
                new String[] {"scan", "1", "30", "60", "rmqbroker:8081"}));
    }

    @Test
    void percentileUsesObservedCompletionTimesOnly() {
        List<Long> sorted = List.of(1L, 2L, 10L, 20L, 30L);
        assertEquals(10L, AutoSettleTimelineProbe.percentile(sorted, 50));
        assertEquals(30L, AutoSettleTimelineProbe.percentile(sorted, 95));
        assertEquals(-1L, AutoSettleTimelineProbe.percentile(List.of(), 99));
    }
}
