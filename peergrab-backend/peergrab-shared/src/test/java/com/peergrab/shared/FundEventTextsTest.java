package com.peergrab.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FundEventTextsTest {

    @Test
    void settlementTextsUseYuanInsteadOfRawCents() {
        assertEquals("任务已完成结算，托管 ¥9.00 已分配给跑腿",
                FundEventTexts.publisherContent("SETTLED", 855, 45));
        assertEquals("任务结算完成，你获得 ¥8.55",
                FundEventTexts.runnerContent("SETTLED", 855));
    }

    @Test
    void refundAndArbitrationTextsUseYuan() {
        assertEquals("任务已退款，¥15.00 已退回你的账户",
                FundEventTexts.publisherContent("REFUNDED", 1500, 0));
        assertEquals("争议已裁决，托管 ¥15.00 已按裁决分配",
                FundEventTexts.publisherContent("ARBITRATED", 1425, 75));
    }

    @Test
    void unknownTypeFallsBackToGenericFundText() {
        assertEquals("任务资金状态变动",
                FundEventTexts.publisherContent("UNKNOWN", 100, 0));
    }
}
