package com.peergrab.bench;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S5TimelineProbeTest {
    @Test
    void nearestRankPercentilesPreserveSignedEarlyDelivery() {
        List<Long> samples = List.of(-10L, 0L, 5L, 10L, 50L);
        assertEquals(5L, S5TimelineProbe.percentile(samples, 50));
        assertEquals(50L, S5TimelineProbe.percentile(samples, 95));
        assertEquals(-1L, S5TimelineProbe.percentile(List.of(), 99));
    }

    @Test
    void incompleteOrDuplicateTransitionsFailTheRun() {
        assertTrue(new S5TimelineProbe.Result(2, 2, 2, 0, 0, 0, 0,
                2, 10, 10, 10, 10).pass());
        assertFalse(new S5TimelineProbe.Result(2, 2, 2, 0, 1, 0, 0,
                2, 10, 10, 10, 10).pass());
        assertFalse(new S5TimelineProbe.Result(2, 2, 1, 1, 0, 0, 1,
                2, 10, 10, 10, 10).pass());
    }

    @Test
    void summaryRetainsTimingInputsAndLabelsUnknownWorkerInterval() {
        var metadata = new S5TimelineProbe.Metadata("fallback", 90, 300, 600,
                1_800_000_000_000L, new S5TimelineProbe.ScanInterval(null,
                "not-exposed-by-container-env"));
        String json = new S5TimelineProbe.Result(2, 2, 2, 0, 0, 0, 0,
                2, 10, 10, 10, 10).json(metadata);
        assertTrue(json.contains("\"leadSeconds\":90"));
        assertTrue(json.contains("\"confirmSeconds\":300"));
        assertTrue(json.contains("\"timeoutAfterDueSeconds\":600"));
        assertTrue(json.contains("\"dueEpochMs\":1800000000000"));
        assertTrue(json.contains("\"workerScanIntervalMs\":null"));
        assertTrue(json.contains("\"workerScanIntervalSource\":\"not-exposed-by-container-env\""));
    }
}
