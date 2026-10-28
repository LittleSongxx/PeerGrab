package com.peergrab.application.usecase;

import com.peergrab.domain.errand.model.Errand;
import com.peergrab.domain.errand.model.ErrandStatus;
import com.peergrab.domain.errand.ports.ErrandRepository;
import com.peergrab.domain.grab.model.GrabRequest;
import com.peergrab.domain.grab.model.GrabRequestStatus;
import com.peergrab.domain.grab.ports.GrabRequestRepository;
import com.peergrab.domain.grab.ports.RunnerRateLimiterPort;
import com.peergrab.shared.ErrorCode;
import com.peergrab.shared.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 持久幂等抢单请求的用例级测试：登记、重放、冲突、过期轮次。 */
class GrabRequestUseCaseTest {

    private static final long CAMPUS_ID = 1L;
    private static final long ERRAND_ID = 9001L;
    private static final long RUNNER_ID = 2001L;
    private static final String REQUEST_ID = "11111111-1111-1111-1111-111111111111";

    private ErrandRepository errands;
    private GrabRequestRepository requests;
    private GrabRequestStep step;
    private GrabErrandUseCase delegate;
    private GrabCommitSideEffects sideEffects;
    private RunnerRateLimiterPort runnerLimiter;
    private GrabRequestUseCase useCase;

    @BeforeEach
    void setUp() {
        errands = mock(ErrandRepository.class);
        requests = mock(GrabRequestRepository.class);
        step = mock(GrabRequestStep.class);
        delegate = mock(GrabErrandUseCase.class);
        sideEffects = mock(GrabCommitSideEffects.class);
        runnerLimiter = mock(RunnerRateLimiterPort.class);
        when(runnerLimiter.checkGrab(anyLong(), anyLong())).thenReturn(RunnerRateLimiterPort.Decision.allowed());
        when(runnerLimiter.checkQuery(anyLong())).thenReturn(RunnerRateLimiterPort.Decision.allowed());
        useCase = new GrabRequestUseCase(errands, requests, step, delegate, sideEffects,
                new SnowflakeIdGenerator(1), runnerLimiter, 10, 30);
    }

    @Test
    @DisplayName("跑腿共享预算耗尽时返回 429 + Retry-After，不登记请求")
    void grab_returns_rate_limited_before_registering() {
        Errand available = errand(ErrandStatus.PUBLISHED, 0, 0);
        when(errands.findById(ERRAND_ID)).thenReturn(Optional.of(available));
        when(requests.find(CAMPUS_ID, RUNNER_ID, REQUEST_ID)).thenReturn(Optional.empty());
        when(runnerLimiter.checkGrab(RUNNER_ID, ERRAND_ID))
                .thenReturn(RunnerRateLimiterPort.Decision.limited(321));

        var result = useCase.grab(ERRAND_ID, RUNNER_ID, REQUEST_ID, 0);

        assertEquals(ErrorCode.GRAB_RATE_LIMITED, result.code());
        assertEquals(429, result.httpStatus());
        assertEquals(321, result.retryAfterMillis());
        verify(step, never()).register(any());
    }

    @Test
    @DisplayName("限流存储不可用时返回 503，不把故障当成额度充足")
    void grab_returns_dependency_unavailable_when_limiter_down() {
        Errand available = errand(ErrandStatus.PUBLISHED, 0, 0);
        when(errands.findById(ERRAND_ID)).thenReturn(Optional.of(available));
        when(requests.find(CAMPUS_ID, RUNNER_ID, REQUEST_ID)).thenReturn(Optional.empty());
        when(runnerLimiter.checkGrab(RUNNER_ID, ERRAND_ID))
                .thenReturn(RunnerRateLimiterPort.Decision.unavailable());

        var result = useCase.grab(ERRAND_ID, RUNNER_ID, REQUEST_ID, 0);

        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, result.code());
        assertEquals(503, result.httpStatus());
        verify(step, never()).register(any());
    }

    @Test
    @DisplayName("查询同样受独立预算约束")
    void query_returns_rate_limited() {
        when(runnerLimiter.checkQuery(RUNNER_ID))
                .thenReturn(RunnerRateLimiterPort.Decision.limited(500));

        var result = useCase.query(ERRAND_ID, RUNNER_ID, REQUEST_ID);

        assertEquals(ErrorCode.GRAB_RATE_LIMITED, result.code());
        assertEquals(429, result.httpStatus());
        assertEquals(500, result.retryAfterMillis());
    }

    @Test
    @DisplayName("首次请求登记后同步执行并收敛为成功，返回 200 + grabbed")
    void grab_first_request_executes_and_finishes_success() {
        Errand errand = errand(ErrandStatus.PUBLISHED, 0, 0);
        GrabRequest processing = request(GrabRequestStatus.PROCESSING, null, 0, 0);
        GrabRequest succeeded = request(GrabRequestStatus.SUCCEEDED, "OK", 200, 0);
        when(errands.findById(ERRAND_ID)).thenReturn(Optional.of(errand));
        when(requests.find(CAMPUS_ID, RUNNER_ID, REQUEST_ID))
                .thenReturn(Optional.empty(), Optional.empty(), Optional.of(processing));
        when(step.register(any())).thenReturn(true);
        when(delegate.grab(any())).thenReturn(new GrabErrandUseCase.Result(ErrorCode.OK, true, null));
        when(step.finish(any(), eq(ErrorCode.OK), eq(200), eq(true))).thenReturn(succeeded);

        var result = useCase.grab(ERRAND_ID, RUNNER_ID, REQUEST_ID, 0);

        assertTrue(result.grabbed());
        assertEquals(ErrorCode.OK, result.code());
        assertEquals("SUCCEEDED", result.status());
        assertEquals(200, result.httpStatus());
        assertFalse(result.replayed());
        verify(step).register(any());
        verify(delegate).grab(any());
    }

    @Test
    @DisplayName("同 requestId 终态重放直接返回持久结果，不再执行抢单")
    void grab_replays_terminal_request_without_executing() {
        Errand errand = errand(ErrandStatus.PUBLISHED, 0, 0);
        GrabRequest succeeded = request(GrabRequestStatus.SUCCEEDED, "OK", 200, 0);
        when(errands.findById(ERRAND_ID)).thenReturn(Optional.of(errand));
        when(requests.find(CAMPUS_ID, RUNNER_ID, REQUEST_ID)).thenReturn(Optional.of(succeeded));

        var result = useCase.grab(ERRAND_ID, RUNNER_ID, REQUEST_ID, 0);

        assertTrue(result.grabbed());
        assertTrue(result.replayed());
        assertEquals(ErrorCode.OK, result.code());
        verify(delegate, never()).grab(any());
        verify(step, never()).register(any());
    }

    @Test
    @DisplayName("同 requestId 但请求内容不同（轮次变化）返回 REQUEST_CONFLICT")
    void grab_rejects_payload_conflict() {
        Errand errand = errand(ErrandStatus.PUBLISHED, 0, 1);
        GrabRequest existing = request(GrabRequestStatus.SUCCEEDED, "OK", 200, 1);
        when(errands.findById(ERRAND_ID)).thenReturn(Optional.of(errand));
        when(requests.find(CAMPUS_ID, RUNNER_ID, REQUEST_ID)).thenReturn(Optional.of(existing));

        var result = useCase.grab(ERRAND_ID, RUNNER_ID, REQUEST_ID, 2);

        assertEquals(ErrorCode.REQUEST_CONFLICT, result.code());
        assertEquals(409, result.httpStatus());
        verify(delegate, never()).grab(any());
    }

    @Test
    @DisplayName("expectedRound 与任务当前轮次不一致时以 STALE_VERSION 终结请求")
    void grab_rejects_stale_round() {
        Errand errand = errand(ErrandStatus.PUBLISHED, 2, 1);
        GrabRequest processing = request(GrabRequestStatus.PROCESSING, null, 0, 1);
        GrabRequest rejected = request(GrabRequestStatus.REJECTED, "STALE_VERSION", 409, 1);
        when(errands.findById(ERRAND_ID)).thenReturn(Optional.of(errand));
        when(requests.find(CAMPUS_ID, RUNNER_ID, REQUEST_ID))
                .thenReturn(Optional.empty(), Optional.empty(), Optional.of(processing));
        when(step.register(any())).thenReturn(true);
        when(step.finish(any(), eq(ErrorCode.STALE_VERSION), eq(409), eq(false))).thenReturn(rejected);

        var result = useCase.grab(ERRAND_ID, RUNNER_ID, REQUEST_ID, 0);

        assertEquals(ErrorCode.STALE_VERSION, result.code());
        assertFalse(result.grabbed());
        verify(delegate, never()).grab(any());
    }

    @Test
    @DisplayName("查询已登记但未终结的请求返回 202 REQUEST_PROCESSING")
    void query_returns_processing_for_live_request() {
        Errand errand = errand(ErrandStatus.PUBLISHED, 0, 0);
        GrabRequest processing = request(GrabRequestStatus.PROCESSING, null, 0, 0);
        when(errands.findById(ERRAND_ID)).thenReturn(Optional.of(errand));
        when(requests.find(CAMPUS_ID, RUNNER_ID, REQUEST_ID)).thenReturn(Optional.of(processing));
        when(requests.databaseNow()).thenReturn(processing.leaseUntil().minusMillis(500));

        var result = useCase.query(ERRAND_ID, RUNNER_ID, REQUEST_ID);

        assertEquals(ErrorCode.REQUEST_PROCESSING, result.code());
        assertEquals(202, result.httpStatus());
        assertEquals(1000, result.retryAfterMillis());
    }

    @Test
    @DisplayName("查询不存在的请求返回 REQUEST_NOT_FOUND")
    void query_returns_not_found_for_unknown_request() {
        Errand errand = errand(ErrandStatus.PUBLISHED, 0, 0);
        when(errands.findById(ERRAND_ID)).thenReturn(Optional.of(errand));
        when(requests.find(CAMPUS_ID, RUNNER_ID, REQUEST_ID)).thenReturn(Optional.empty());

        var result = useCase.query(ERRAND_ID, RUNNER_ID, REQUEST_ID);

        assertEquals(ErrorCode.REQUEST_NOT_FOUND, result.code());
        assertEquals(404, result.httpStatus());
    }

    private static Errand errand(ErrandStatus status, int round, long version) {
        Errand errand = mock(Errand.class);
        when(errand.id()).thenReturn(ERRAND_ID);
        when(errand.campusId()).thenReturn(CAMPUS_ID);
        when(errand.publisherId()).thenReturn(1001L);
        when(errand.status()).thenReturn(status);
        when(errand.round()).thenReturn(round);
        when(errand.version()).thenReturn(version);
        return errand;
    }

    private static GrabRequest request(GrabRequestStatus status, String resultCode, int httpStatus, int round) {
        Instant now = Instant.now();
        return new GrabRequest(1L, CAMPUS_ID, ERRAND_ID, RUNNER_ID, REQUEST_ID,
                hash(round), round, 0L, status, "token-" + status,
                now.plusSeconds(30), resultCode, httpStatus, "{}", now,
                status == GrabRequestStatus.PROCESSING ? null : now);
    }

    private static String hash(int round) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(("GRAB:" + ERRAND_ID + ":" + round).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
