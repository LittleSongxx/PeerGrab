package com.peergrab.worker;

import com.peergrab.shared.MessagePayloadCodec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class AutoSettleConsumerTest {

    @Test
    void parsesLegacyUnquotedNumericErrandIdFromMqPayload() {
        long errandId = assertDoesNotThrow(() ->
                MessagePayloadCodec.readAutoSettle("{\"errandId\": 215872245673758720}").errandId());

        assertEquals(215872245673758720L, errandId);
    }
}
