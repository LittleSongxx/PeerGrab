package com.peergrab.domain;

import com.peergrab.domain.errand.model.ArbitrationFavor;
import com.peergrab.shared.BizException;
import com.peergrab.shared.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArbitrationFavorTest {

    @Test
    @DisplayName("合法裁决方向大小写与空白容忍")
    void parsesLegalValues() {
        assertEquals(ArbitrationFavor.RUNNER, ArbitrationFavor.fromWire("RUNNER"));
        assertEquals(ArbitrationFavor.RUNNER, ArbitrationFavor.fromWire(" runner "));
        assertEquals(ArbitrationFavor.PUBLISHER, ArbitrationFavor.fromWire("publisher"));
    }

    @Test
    @DisplayName("非法/缺失裁决方向必须拒绝而不是默认判给某方")
    void rejectsIllegalValues() {
        assertRejected(null);
        assertRejected("");
        assertRejected("   ");
        assertRejected("RUNNERS");
        assertRejected("publisher;");
        assertRejected("0");
    }

    private static void assertRejected(String value) {
        BizException ex = assertThrows(BizException.class, () -> ArbitrationFavor.fromWire(value));
        assertEquals(ErrorCode.INVALID_ARGUMENT, ex.code());
    }
}
