package com.peergrab.application.usecase;

import com.peergrab.domain.errand.ports.ErrandRepository;
import com.peergrab.domain.grab.model.GrabRecord;
import com.peergrab.domain.grab.model.GrabRequest;
import com.peergrab.domain.grab.model.GrabRequestStatus;
import com.peergrab.domain.grab.ports.GrabRecordRepository;
import com.peergrab.domain.grab.ports.GrabRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 恢复裁决：以 grab_record + requestId 为“业务是否已提交”的唯一证据。 */
class GrabRequestStepTest {

    private static final long CAMPUS_ID = 1L;
    private static final long ERRAND_ID = 9001L;
    private static final long RUNNER_ID = 2001L;
    private static final String REQUEST_ID = "22222222-2222-2222-2222-222222222222";

    private GrabRequestRepository requests;
    private GrabRecordRepository records;
    private GrabRequestStep step;

    @BeforeEach
    void setUp() {
        requests = mock(GrabRequestRepository.class);
        records = mock(GrabRecordRepository.class);
        step = new GrabRequestStep(requests, records, mock(ErrandRepository.class));
    }

    @Test
    @DisplayName("业务已提交：恢复为 SUCCEEDED 并返回 recoveredSuccess=true")
    void reconcile_recovers_committed_request() {
        GrabRequest processing = request(GrabRequestStatus.PROCESSING, 0);
        GrabRequest succeeded = request(GrabRequestStatus.SUCCEEDED, 0);
        when(requests.lockById(CAMPUS_ID, 1L)).thenReturn(Optional.of(processing));
        when(records.findGrabbedByRequestId(CAMPUS_ID, ERRAND_ID, REQUEST_ID, 0))
                .thenReturn(Optional.of(GrabRecord.grabbed(7L, CAMPUS_ID, ERRAND_ID, RUNNER_ID, 1, 0, REQUEST_ID)));
        when(requests.finishRecovered(eq(CAMPUS_ID), eq(1L), eq(GrabRequestStatus.SUCCEEDED),
                eq("OK"), eq(200), anyString())).thenReturn(true);
        when(requests.findById(CAMPUS_ID, 1L)).thenReturn(Optional.of(succeeded));

        var result = step.reconcile(processing);

        assertTrue(result.recoveredSuccess());
        assertEquals(GrabRequestStatus.SUCCEEDED, result.request().status());
        verify(requests).finishRecovered(eq(CAMPUS_ID), eq(1L), eq(GrabRequestStatus.SUCCEEDED),
                eq("OK"), eq(200), anyString());
    }

    @Test
    @DisplayName("业务未提交：恢复为 EXPIRED，不臆断成功")
    void reconcile_expires_uncommitted_request() {
        GrabRequest processing = request(GrabRequestStatus.PROCESSING, 0);
        GrabRequest expired = request(GrabRequestStatus.EXPIRED, 0);
        when(requests.lockById(CAMPUS_ID, 1L)).thenReturn(Optional.of(processing));
        when(records.findGrabbedByRequestId(CAMPUS_ID, ERRAND_ID, REQUEST_ID, 0)).thenReturn(Optional.empty());
        when(requests.finishRecovered(eq(CAMPUS_ID), eq(1L), eq(GrabRequestStatus.EXPIRED),
                eq("REQUEST_EXPIRED"), eq(409), anyString())).thenReturn(true);
        when(requests.findById(CAMPUS_ID, 1L)).thenReturn(Optional.of(expired));

        var result = step.reconcile(processing);

        assertFalse(result.recoveredSuccess());
        assertEquals(GrabRequestStatus.EXPIRED, result.request().status());
        verify(requests).finishRecovered(eq(CAMPUS_ID), eq(1L), eq(GrabRequestStatus.EXPIRED),
                eq("REQUEST_EXPIRED"), eq(409), anyString());
    }

    @Test
    @DisplayName("已被其他线程终结的请求直接返回当前状态，不重复写")
    void reconcile_is_idempotent_on_terminal_request() {
        GrabRequest succeeded = request(GrabRequestStatus.SUCCEEDED, 0);
        when(requests.lockById(CAMPUS_ID, 1L)).thenReturn(Optional.of(succeeded));

        var result = step.reconcile(succeeded);

        assertFalse(result.recoveredSuccess());
        assertEquals(GrabRequestStatus.SUCCEEDED, result.request().status());
        verify(requests, never()).finishRecovered(anyLong(), anyLong(), any(), anyString(), anyInt(), anyString());
    }

    private static GrabRequest request(GrabRequestStatus status, int round) {
        Instant now = Instant.now();
        return new GrabRequest(1L, CAMPUS_ID, ERRAND_ID, RUNNER_ID, REQUEST_ID,
                "hash-" + round, round, 0L, status, "token",
                now.minusSeconds(1), status == GrabRequestStatus.SUCCEEDED ? "OK" : null,
                status == GrabRequestStatus.SUCCEEDED ? 200 : 0, "{}", now,
                status == GrabRequestStatus.PROCESSING ? null : now);
    }
}
