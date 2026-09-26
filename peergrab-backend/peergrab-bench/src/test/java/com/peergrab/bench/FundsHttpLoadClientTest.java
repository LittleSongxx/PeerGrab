package com.peergrab.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FundsHttpLoadClientTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void boundsAndOriginAreValidatedBeforeAnyConnection() {
        var config = FundsHttpLoadClient.parse(new String[]{"http://127.0.0.1:28080/", "1", "1"});
        assertEquals("http://127.0.0.1:28080", config.baseUrl());
        assertEquals(8, config.sameTaskAttempts());
        assertEquals(200, FundsHttpLoadClient.parse(new String[]{
                "http://127.0.0.1:28080", "1", "1", "200"}).sameTaskAttempts());
        assertThrows(IllegalArgumentException.class, () -> FundsHttpLoadClient.parse(
                new String[]{"http://127.0.0.1:28080/api", "1", "1"}));
        assertThrows(IllegalArgumentException.class, () -> FundsHttpLoadClient.parse(
                new String[]{"http://127.0.0.1:28080", "501", "1"}));
        assertThrows(IllegalArgumentException.class, () -> FundsHttpLoadClient.parse(
                new String[]{"http://127.0.0.1:28080", "1", "65"}));
        assertThrows(IllegalArgumentException.class, () -> FundsHttpLoadClient.parse(
                new String[]{"http://127.0.0.1:28080", "1", "1", "201"}));
    }

    @Test
    void successfulIdempotentAndSystemOutcomesAreSeparated() throws Exception {
        assertEquals("SETTLED", FundsHttpLoadClient.classify(reply(200, "OK", "SETTLED")));
        assertEquals("BUSINESS", FundsHttpLoadClient.classify(reply(200, "OK", "ALREADY_SETTLED")));
        assertTrue(FundsHttpLoadClient.isDuplicateRejection(reply(200, "OK", "ALREADY_SETTLED")));
        assertTrue(FundsHttpLoadClient.isDuplicateRejection(reply(200, "OK", "CONFLICT")));
        assertFalse(FundsHttpLoadClient.isDuplicateRejection(reply(409, "NOT_PUBLISHER", "")));
        assertEquals("BUSINESS", FundsHttpLoadClient.classify(reply(409, "SETTLE_CONFLICT", "")));
        assertEquals("SYSTEM", FundsHttpLoadClient.classify(reply(500, "INTERNAL_ERROR", "")));
        assertEquals("SYSTEM", FundsHttpLoadClient.classify(reply(200, "OK", "unexpected")));
    }

    @Test
    void persistedRunParametersIncludeAllLoadDimensions() {
        var config = FundsHttpLoadClient.parse(new String[]{
                "http://127.0.0.1:28080", "500", "64", "200", "15000"});
        var values = FundsHttpLoadClient.runParameters(config);
        assertEquals(500, values.get("count"));
        assertEquals(64, values.get("concurrency"));
        assertEquals(200, values.get("sameTaskAttempts"));
        assertEquals(15000, values.get("timeoutMillis"));
        assertEquals(100, values.get("rewardCents"));
    }

    @Test
    void acceptsPositiveNumericIdsSerializedAsStringOrNumber() throws Exception {
        assertEquals(123L, FundsHttpLoadClient.parseErrandId(json.readTree("\"123\"")));
        assertEquals(123L, FundsHttpLoadClient.parseErrandId(json.readTree("123")));
        assertThrows(IllegalStateException.class,
                () -> FundsHttpLoadClient.parseErrandId(json.readTree("\"12x\"")));
        assertThrows(IllegalStateException.class,
                () -> FundsHttpLoadClient.parseErrandId(json.readTree("0")));
        assertThrows(IllegalStateException.class,
                () -> FundsHttpLoadClient.parseErrandId(json.readTree("null")));
    }

    private FundsHttpLoadClient.Reply reply(int status, String code, String result) throws Exception {
        return new FundsHttpLoadClient.Reply(status, code,
                json.readTree("{\"result\":\"" + result + "\"}"), 1_000);
    }
}
