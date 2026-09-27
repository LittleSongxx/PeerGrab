package com.peergrab.worker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdbcScanRetryQueueTest {

    @Test
    void retry_backoff_is_bounded_while_attempts_continue() {
        assertEquals(5, JdbcScanRetryQueue.backoffSeconds(1));
        assertEquals(10, JdbcScanRetryQueue.backoffSeconds(2));
        assertEquals(300, JdbcScanRetryQueue.backoffSeconds(7));
        assertEquals(300, JdbcScanRetryQueue.backoffSeconds(30));
    }
}
