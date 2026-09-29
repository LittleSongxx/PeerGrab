package com.peergrab.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixedWorkloadClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void oneCycleHasDocumentedOperationWeights() {
        Map<MixedWorkloadClient.Operation, Integer> counts =
                new EnumMap<>(MixedWorkloadClient.Operation.class);
        for (int i = 0; i < 60; i++) {
            counts.merge(MixedWorkloadClient.chooseOperation(i), 1, Integer::sum);
        }
        assertEquals(12, counts.get(MixedWorkloadClient.Operation.LIST_FIRST));
        assertEquals(4, counts.get(MixedWorkloadClient.Operation.LIST_PAGE_5));
        assertEquals(4, counts.get(MixedWorkloadClient.Operation.LIST_PAGE_20));
        assertEquals(4, counts.get(MixedWorkloadClient.Operation.LIST_PAGE_100));
        assertEquals(30, counts.get(MixedWorkloadClient.Operation.DETAIL));
        assertEquals(6, counts.get(MixedWorkloadClient.Operation.PUBLISH));
    }

    @Test
    void businessValidationRejectsWrongDetailAndEmptyCursorPage() throws Exception {
        var wrongId = JSON.readTree("{\"code\":\"OK\",\"data\":{\"id\":\"42\",\"status\":\"PUBLISHED\"}}");
        assertFalse(MixedWorkloadClient.validResponse(
                MixedWorkloadClient.Operation.DETAIL, 200, wrongId, 43));
        assertTrue(MixedWorkloadClient.validResponse(
                MixedWorkloadClient.Operation.DETAIL, 200, wrongId, 42));
        var empty = JSON.readTree("{\"code\":\"OK\",\"data\":{\"items\":[],\"nextCursor\":\"x\"}}");
        assertFalse(MixedWorkloadClient.validResponse(
                MixedWorkloadClient.Operation.LIST_PAGE_20, 200, empty, 0));
    }

    @Test
    void refusesUnboundedOrNonHttpLoadConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> MixedWorkloadClient.parse(
                new String[]{"https://example.com", "100", "30", "60", "100"}));
        assertThrows(IllegalArgumentException.class, () -> MixedWorkloadClient.parse(
                new String[]{"http://127.0.0.1:28080", "5000", "30", "3600", "100"}));
    }

    @Test
    void readsStringEncodedLongMetricsFromTheInternalApi() throws Exception {
        var stats = JSON.readTree("{\"requests\":\"5000\",\"dbLoads\":100}");
        assertEquals(5000L, MixedWorkloadClient.cacheMetric(stats, "requests"));
        assertEquals(100L, MixedWorkloadClient.cacheMetric(stats, "dbLoads"));
        assertThrows(IllegalStateException.class,
                () -> MixedWorkloadClient.cacheMetric(stats, "missing"));
        assertThrows(IllegalStateException.class,
                () -> MixedWorkloadClient.cacheMetric(JSON.readTree("{\"requests\":\"-1\"}"), "requests"));
    }
}
