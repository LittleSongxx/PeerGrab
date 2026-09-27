package com.peergrab.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessagePayloadCodecTest {

    @Test
    void acceptsLegacyBodiesAndLargeIdsWithoutPrecisionLoss() {
        long id = 9_223_372_036_854_775_000L;
        assertEquals(id, MessagePayloadCodec.readTimeout(
                "{ \"version\": 3, \"round\": 2, \"errandId\": \"" + id + "\" }").errandId());
        assertEquals(id, MessagePayloadCodec.readAutoSettle(
                "{\"errandId\":" + id + "}").errandId());
        assertEquals(id, MessagePayloadCodec.readCacheEvict(
                "{\"errandId\":" + id + "}").errandId());
        assertEquals(id, MessagePayloadCodec.readFundEvent("""
                {"bizNo":"settle:1","type":"SETTLED","errandId":9223372036854775000,
                 "publisherId":1001,"runnerId":2001,"amountCents":950,"commissionCents":50}
                """).errandId());
    }

    @Test
    void rejectsMissingFieldsAndUnknownVersion() {
        assertThrows(IllegalArgumentException.class,
                () -> MessagePayloadCodec.readTimeout("{\"errandId\":1,\"round\":2}"));
        assertThrows(IllegalArgumentException.class,
                () -> MessagePayloadCodec.readAutoSettle("{\"schemaVersion\":99,\"errandId\":1}"));
        assertThrows(IllegalArgumentException.class,
                () -> MessagePayloadCodec.readFundEvent("{\"type\":\"SETTLED\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> MessagePayloadCodec.readCacheEvict("{invalid}"));
    }

    @Test
    void emitsVersionAndEscapesBizKey() {
        var event = MessagePayloadCodec.readFundEvent(MessagePayloadCodec.fundEvent(
                "settle:\"\\", "SETTLED", 1, 2, 3, 4, 5));
        assertEquals("settle:\"\\", event.bizNo());
        assertEquals(1, event.schemaVersion());
    }
}
