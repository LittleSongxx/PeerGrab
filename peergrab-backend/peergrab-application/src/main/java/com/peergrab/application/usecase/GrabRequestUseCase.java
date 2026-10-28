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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * 持久幂等抢单请求用例（v2）。
 *
 * <p>在原有"Redis 预占 + DB CAS"链路外面加一层数据库请求事实：先登记 requestId，
 * 再同步执行原抢单用例，最后把结果写回请求行。这样即使"进程崩溃/响应丢失/客户端重试"，
 * 同一个 requestId 也只会收敛到一个确定结果；Redis 仍负责挡流量，数据库业务事实仍是终裁。</p>
 *
 * <p>与 v1 的兼容策略：旧的 {@code POST /api/errands/{id}/grab} 保持不变；
 * v2 使用独立端点 {@code /grab-v2} 与查询端点，便于灰度与回滚。</p>
 */
@Service
public class GrabRequestUseCase {

    private static final Logger log = LoggerFactory.getLogger(GrabRequestUseCase.class);

    private final ErrandRepository errands;
    private final GrabRequestRepository requests;
    private final GrabRequestStep step;
    private final GrabErrandUseCase delegate;
    private final GrabCommitSideEffects sideEffects;
    private final SnowflakeIdGenerator ids;
    private final RunnerRateLimiterPort runnerLimiter;
    private final int leaseSeconds;
    private final Semaphore grabCapacity;

    @org.springframework.beans.factory.annotation.Autowired
    public GrabRequestUseCase(ErrandRepository errands,
                              GrabRequestRepository requests,
                              GrabRequestStep step,
                              GrabErrandUseCase delegate,
                              GrabCommitSideEffects sideEffects,
                              SnowflakeIdGenerator ids,
                              RunnerRateLimiterPort runnerLimiter,
                              @Value("${peergrab.grab-request.max-in-flight:10}") int maxInFlight,
                              @Value("${peergrab.grab-request.lease-seconds:30}") int leaseSeconds) {
        if (maxInFlight < 1 || leaseSeconds < 1) {
            throw new IllegalArgumentException("抢单请求容量与租约必须为正数");
        }
        this.errands = errands;
        this.requests = requests;
        this.step = step;
        this.delegate = delegate;
        this.sideEffects = sideEffects;
        this.ids = ids;
        this.runnerLimiter = runnerLimiter;
        this.leaseSeconds = leaseSeconds;
        this.grabCapacity = new Semaphore(maxInFlight);
    }

    /**
     * @param expectedRound 客户端发起请求时看到的轮次；轮次不匹配说明任务已被流转，拒绝执行旧请求
     */
    public Result grab(long errandId, long runnerId, String requestId, int expectedRound) {
        if (!valid(errandId, runnerId, requestId, expectedRound)) {
            return response(ErrorCode.INVALID_ARGUMENT, requestId, 400);
        }
        Errand errand = errands.findById(errandId).orElse(null);
        if (errand == null) {
            return response(ErrorCode.ERRAND_NOT_FOUND, requestId, 404);
        }
        String hash = fingerprint(errandId, expectedRound);
        var existing = requests.find(errand.campusId(), runnerId, requestId);
        if (existing.isPresent()) {
            // 已完成请求的幂等重放优先于抢单预算：否则客户端在响应丢失后重试，
            // 可能因为预算耗尽拿不到本已确定的结果。重放本身仍受查询预算约束。
            RunnerRateLimiterPort.Decision replayLimit = runnerLimiter.checkQuery(runnerId);
            if (replayLimit.status() == RunnerRateLimiterPort.Status.LIMITED) {
                return response(ErrorCode.GRAB_RATE_LIMITED, requestId, 429, replayLimit.retryAfterMillis());
            }
            if (replayLimit.status() == RunnerRateLimiterPort.Status.UNAVAILABLE) {
                return response(ErrorCode.DEPENDENCY_UNAVAILABLE, requestId, 503);
            }
            return replay(existing.get(), errandId, hash);
        }
        // 新尝试才消耗抢单预算；放在任务读取之后、请求登记之前，避免过热请求写库。
        RunnerRateLimiterPort.Decision limit = runnerLimiter.checkGrab(runnerId, errandId);
        if (limit.status() == RunnerRateLimiterPort.Status.LIMITED) {
            return response(ErrorCode.GRAB_RATE_LIMITED, requestId, 429, limit.retryAfterMillis());
        }
        if (limit.status() == RunnerRateLimiterPort.Status.UNAVAILABLE) {
            return response(ErrorCode.DEPENDENCY_UNAVAILABLE, requestId, 503);
        }
        // 登记发生在 Redis 占位前，必须先限制整条 DB 编排并发量，避免把连接池打满后
        // 连恢复查询都拿不到连接。满载返回 503，客户端保留原 ID 重试。
        if (!grabCapacity.tryAcquire()) {
            return response(ErrorCode.DEPENDENCY_UNAVAILABLE, requestId, 503);
        }
        try {
            existing = requests.find(errand.campusId(), runnerId, requestId);
            if (existing.isPresent()) {
                return replay(existing.get(), errandId, hash);
            }
            var newRequest = new GrabRequest.NewRequest(ids.nextId(), errand.campusId(), errandId, runnerId,
                    requestId, hash, expectedRound, errand.version(),
                    UUID.randomUUID().toString(), leaseSeconds);
            if (!step.register(newRequest)) {
                // 并发同 requestId 的竞争失败方：直接按已登记请求重放。
                var raced = requests.find(errand.campusId(), runnerId, requestId)
                        .orElseThrow(() -> new IllegalStateException("请求登记冲突但读取不到已有请求"));
                return replay(raced, errandId, hash);
            }
            GrabRequest registered = requests.find(errand.campusId(), runnerId, requestId).orElseThrow();
            if (expectedRound != errand.round()) {
                return terminalReject(registered, ErrorCode.STALE_VERSION, 409);
            }
            if (errand.status() != ErrandStatus.PUBLISHED) {
                ErrorCode code = errand.status() == ErrandStatus.LOCKED
                        ? ErrorCode.SLOT_FULL : ErrorCode.ERRAND_NOT_GRABBABLE;
                return terminalReject(registered, code, 409);
            }
            var grabbed = delegate.grab(new GrabErrandUseCase.Command(errandId, runnerId, requestId));
            if (grabbed.code() == ErrorCode.OK) {
                GrabRequest done = step.finish(registered, ErrorCode.OK, 200, true);
                return toResult(done, false);
            }
            GrabRequest done = step.finish(registered, grabbed.code(), httpStatus(grabbed.code()), false);
            return toResult(done, false);
        } catch (RuntimeException e) {
            // 业务成败未知时绝不猜测：保留请求行 PROCESSING，由查询/恢复线程按数据库事实收敛。
            log.warn("抢单请求结果暂不确定，保留持久请求待恢复 errandId={} requestId={}", errandId, requestId, e);
            var current = requests.find(errand.campusId(), runnerId, requestId);
            if (current.isPresent() && current.get().status().terminal()) {
                return toResult(current.get(), true);
            }
            return response(ErrorCode.REQUEST_PROCESSING, requestId, 202);
        } finally {
            grabCapacity.release();
        }
    }

    /** 客户端用原 requestId 查询结果；这是"202/网络失败后恢复"的正式入口。 */
    public Result query(long errandId, long runnerId, String requestId) {
        if (!valid(errandId, runnerId, requestId, 0)) {
            return response(ErrorCode.INVALID_ARGUMENT, requestId, 400);
        }
        // 查询也必须有界：否则 202/网络失败后的轮询可以放大 Redis/DB 压力。
        RunnerRateLimiterPort.Decision queryLimit = runnerLimiter.checkQuery(runnerId);
        if (queryLimit.status() == RunnerRateLimiterPort.Status.LIMITED) {
            return response(ErrorCode.GRAB_RATE_LIMITED, requestId, 429, queryLimit.retryAfterMillis());
        }
        if (queryLimit.status() == RunnerRateLimiterPort.Status.UNAVAILABLE) {
            return response(ErrorCode.DEPENDENCY_UNAVAILABLE, requestId, 503);
        }
        Errand errand = errands.findById(errandId).orElse(null);
        if (errand == null) {
            return response(ErrorCode.ERRAND_NOT_FOUND, requestId, 404);
        }
        var found = requests.find(errand.campusId(), runnerId, requestId);
        if (found.isEmpty() || found.get().errandId() != errandId) {
            return response(ErrorCode.REQUEST_NOT_FOUND, requestId, 404);
        }
        GrabRequest request = found.get();
        return replay(request, errandId, request.payloadHash());
    }

    private Result replay(GrabRequest request, long errandId, String expectedHash) {
        if (request.errandId() != errandId || !request.payloadHash().equals(expectedHash)) {
            return response(ErrorCode.REQUEST_CONFLICT, request.requestId(), 409);
        }
        if (!request.status().terminal() && !request.leaseUntil().isAfter(requests.databaseNow())) {
            var reconciled = step.reconcile(request);
            request = reconciled.request();
            if (reconciled.recoveredSuccess()) {
                // 业务其实已提交，只是响应/请求终结没完成：补跑超时消息、缓存失效与通知。
                sideEffects.run(request.errandId());
            }
        }
        return toResult(request, true);
    }

    private Result terminalReject(GrabRequest request, ErrorCode code, int httpStatus) {
        GrabRequest done = step.finish(request, code, httpStatus, false);
        return toResult(done, false);
    }

    private Result toResult(GrabRequest request, boolean replayed) {
        if (request.status() == GrabRequestStatus.PROCESSING) {
            long retryAfter = Math.max(
                    request.leaseUntil().toEpochMilli() - requests.databaseNow().toEpochMilli(), 1000);
            return new Result(request.requestId(), GrabRequestStatus.PROCESSING.name(),
                    ErrorCode.REQUEST_PROCESSING, false, null, replayed, retryAfter, 202);
        }
        boolean grabbed = request.status() == GrabRequestStatus.SUCCEEDED;
        return new Result(request.requestId(), request.status().name(), parseCode(request.resultCode()),
                grabbed, request.requestRound(), replayed, 0,
                request.httpStatus() <= 0 ? 409 : request.httpStatus());
    }

    private static Result response(ErrorCode code, String requestId, int httpStatus) {
        return response(code, requestId, httpStatus, httpStatus == 202 ? 1000 : 0);
    }

    private static Result response(ErrorCode code, String requestId, int httpStatus, long retryAfterMillis) {
        return new Result(requestId, httpStatus == 202 ? GrabRequestStatus.PROCESSING.name() : null,
                code, false, null, false, retryAfterMillis, httpStatus);
    }

    private static ErrorCode parseCode(String code) {
        if (code == null || code.isBlank()) {
            return ErrorCode.GRAB_CONFLICT;
        }
        try {
            return ErrorCode.valueOf(code);
        } catch (IllegalArgumentException e) {
            return ErrorCode.GRAB_CONFLICT;
        }
    }

    private static int httpStatus(ErrorCode code) {
        return switch (code) {
            case OK -> 200;
            case ERRAND_NOT_FOUND, REQUEST_NOT_FOUND -> 404;
            case GRAB_RATE_LIMITED -> 429;
            case SELF_GRAB_FORBIDDEN, ROLE_NOT_ALLOWED, CREDIT_TOO_LOW -> 403;
            case INVALID_ARGUMENT -> 400;
            case DEPENDENCY_UNAVAILABLE -> 503;
            default -> 409;
        };
    }

    private static boolean valid(long errandId, long runnerId, String requestId, int expectedRound) {
        if (errandId <= 0 || runnerId <= 0 || expectedRound < 0 || requestId == null) {
            return false;
        }
        try {
            return UUID.fromString(requestId).toString().equals(requestId);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 同一 requestId 只允许对应同一 (errand, round)，避免"换任务/换轮次"的重放被误判成功。 */
    private static String fingerprint(long errandId, int expectedRound) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("GRAB:" + errandId + ":" + expectedRound).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    public record Result(String requestId, String status, ErrorCode code, boolean grabbed,
                         Integer appliedRound, boolean replayed, long retryAfterMillis, int httpStatus) {
    }
}
