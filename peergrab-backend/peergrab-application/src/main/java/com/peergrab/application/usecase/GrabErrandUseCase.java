package com.peergrab.application.usecase;

import com.peergrab.domain.credit.ports.CreditRepository;
import com.peergrab.domain.errand.model.Errand;
import com.peergrab.domain.errand.model.ErrandStatus;
import com.peergrab.domain.errand.ports.ErrandQueryPort;
import com.peergrab.domain.errand.ports.ErrandRepository;
import com.peergrab.domain.notify.ports.RealtimeNotifier;
import com.peergrab.domain.grab.model.SlotOutcome;
import com.peergrab.domain.grab.ports.CandidateQueuePort;
import com.peergrab.domain.grab.ports.GrabRateLimiterPort;
import com.peergrab.domain.grab.ports.GrabRecordRepository;
import com.peergrab.domain.grab.ports.GrabSlotPort;
import com.peergrab.shared.ErrorCode;
import com.peergrab.shared.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 抢单用例——整个项目的心脏。
 *
 * 四层防护（架构文档 6.3）：
 *   L1 限流         —— Sentinel 热点参数限流，P7 引入
 *   L2 资格前置     —— 信用分门槛与在途任务数，P5 引入完整规则
 *   L3 Redis Lua    —— 原子判定：状态校验 + 名额扣减 + 抢中者写入 + 幂等去重
 *   L4 MySQL CAS    —— 最终裁决：状态与版本号双条件 + grab_record 双唯一索引
 *
 * 关键设计：L3 成功但 L4 失败时必须回滚 Redis 名额，否则名额永久泄漏。
 * 补偿动作放在事务外执行——事务已经回滚了，补偿是独立动作。
 */
@Service
public class GrabErrandUseCase {

    private static final Logger log = LoggerFactory.getLogger(GrabErrandUseCase.class);

    private final GrabSlotPort grabSlotPort;
    private final GrabRecordRepository grabRecordRepository;
    private final CandidateQueuePort candidateQueue;
    private final ErrandRepository errandRepository;
    private final GrabTransactionalStep transactionalStep;
    private final SnowflakeIdGenerator idGenerator;
    private final CacheEvictSupport cacheEvict;
    /** P2 起：抢中后要给抢中者一个确认窗口，超时则流转给候选人 */
    private final TimeoutTransferUseCase timeoutTransferUseCase;
    /** P5 起：资格校验读后端信用分，不再信任客户端传参 */
    private final CreditRepository creditRepository;
    private final ErrandQueryPort errandQueryPort;
    private final int minCreditScore;
    private final int maxOngoing;
    private final RealtimeNotifier notifier;
    private final GrabRateLimiterPort rateLimiter;

    public GrabErrandUseCase(GrabSlotPort grabSlotPort,
                             GrabRecordRepository grabRecordRepository,
                             CandidateQueuePort candidateQueue,
                             ErrandRepository errandRepository,
                             GrabTransactionalStep transactionalStep,
                             SnowflakeIdGenerator idGenerator,
                             TimeoutTransferUseCase timeoutTransferUseCase,
                              CacheEvictSupport cacheEvict,
                              CreditRepository creditRepository,
                              ErrandQueryPort errandQueryPort,
                              @org.springframework.beans.factory.annotation.Value("${peergrab.credit.min-score:40}") int minCreditScore,
                              @org.springframework.beans.factory.annotation.Value("${peergrab.credit.max-ongoing:5}") int maxOngoing,
                              RealtimeNotifier notifier,
                              GrabRateLimiterPort rateLimiter) {
        this.grabSlotPort = grabSlotPort;
        this.grabRecordRepository = grabRecordRepository;
        this.candidateQueue = candidateQueue;
        this.errandRepository = errandRepository;
        this.transactionalStep = transactionalStep;
        this.idGenerator = idGenerator;
        this.timeoutTransferUseCase = timeoutTransferUseCase;
        this.cacheEvict = cacheEvict;
        this.creditRepository = creditRepository;
        this.errandQueryPort = errandQueryPort;
        this.minCreditScore = minCreditScore;
        this.maxOngoing = maxOngoing;
        this.notifier = notifier;
        this.rateLimiter = rateLimiter;
    }

    /**
     * creditScore 参数自 P5 起被忽略：信用分改由后端从 credit_score 读取，
     * 客户端传的分值不可信（伪造高分就能绕过资格门槛、垄断流转机会）。
     * 保留该参数只为兼容既有调用，新代码请用三参构造器。
     */
    public record Command(long errandId, long runnerId, String requestId, int creditScore) {
        public Command(long errandId, long runnerId, String requestId) {
            this(errandId, runnerId, requestId, 0);
        }
    }

    public record Result(ErrorCode code, boolean grabbed, Long candidateRank) {
        static Result success() {
            return new Result(ErrorCode.OK, true, null);
        }
        static Result failed(ErrorCode code, Long rank) {
            return new Result(code, false, rank);
        }
    }

    public Result grab(Command cmd) {
        if (cmd.requestId() == null || cmd.requestId().isBlank() || cmd.requestId().length() > 128) {
            return Result.failed(ErrorCode.INVALID_ARGUMENT, null);
        }

        // L1：热点任务限流。放在所有下游调用之前，避免过热任务继续消耗 Redis 与 DB。
        if (!rateLimiter.tryPass(cmd.errandId(), cmd.runnerId())) {
            return Result.failed(ErrorCode.GRAB_RATE_LIMITED, null);
        }

        // 发单人不能领取自己的任务。必须在 Lua 扣名额前拒绝，避免自抢后资金回流。
        Errand target = errandRepository.findById(cmd.errandId()).orElse(null);
        if (target == null) {
            return Result.failed(ErrorCode.ERRAND_NOT_FOUND, null);
        }
        if (mayHaveRecordedGrab(target)) {
            Result replay = persistedResult(target.campusId(), cmd);
            if (replay != null) return replay;
        }
        if (target.publisherId() == cmd.runnerId()) {
            return Result.failed(ErrorCode.SELF_GRAB_FORBIDDEN, null);
        }
        if (target.grabberId() != null && target.grabberId() == cmd.runnerId()) {
            return Result.failed(ErrorCode.ALREADY_GRABBED, null);
        }

        // L2：资格前置校验（P5 起读后端数据，不再信任客户端传的 creditScore）。
        // 放在 Lua 判定之前：没资格的人连名额裁决都不该参与，省 Redis 压力
        int score = creditRepository.scoreOf(cmd.runnerId());
        if (score < minCreditScore) {
            return Result.failed(ErrorCode.CREDIT_TOO_LOW, null);
        }
        if (errandQueryPort.countOngoingByRunner(cmd.runnerId()) >= maxOngoing) {
            return Result.failed(ErrorCode.TOO_MANY_ONGOING, null);
        }

        // L3：Redis Lua 原子判定。绝大多数失败请求在这一步就被挡住，不会打到数据库
        SlotOutcome outcome;
        try {
            outcome = grabSlotPort.tryAcquire(cmd.errandId(), cmd.runnerId(), cmd.requestId());
        } catch (RuntimeException e) {
            // Redis 暂不可用时仍由 DB CAS 裁决，不能让开放任务永久失去抢单能力。
            log.warn("Redis 名额裁决不可用，走数据库 CAS errandId={}", cmd.errandId(), e);
            outcome = SlotOutcome.SLOT_MISSING;
        }

        switch (outcome) {
            case SLOT_FULL -> {
                // Redis 可能保存着回退前的 0；DB 仍开放时让 CAS 直接裁决。
                // 并发预占位尚未落库也可能短暂出现这个状态，CAS 仍保证不超卖。
                Errand current = errandRepository.findById(cmd.errandId()).orElse(null);
                if (current == null || current.status() != ErrandStatus.PUBLISHED || !current.slotAvailable()) {
                    return classifyUnavailable(cmd);
                }
                try {
                    if (grabSlotPort.reservationPending(cmd.errandId())) {
                        return Result.failed(ErrorCode.GRAB_CONFLICT, null);
                    }
                } catch (RuntimeException e) {
                    log.warn("Redis 预占位状态不可读，走数据库 CAS errandId={}", cmd.errandId(), e);
                }
                outcome = SlotOutcome.SLOT_MISSING;
            }
            case ALREADY_GRABBED -> {
                Errand current = errandRepository.findById(cmd.errandId()).orElse(null);
                return current != null && current.status() == ErrandStatus.PUBLISHED
                        ? Result.failed(ErrorCode.GRAB_CONFLICT, null)
                        : Result.failed(ErrorCode.ALREADY_GRABBED, null);
            }
            case DUPLICATE_REQUEST -> {
                // Redis 只有预占位，不能把尚未提交的请求回复为成功。
                Result committed = persistedResult(target.campusId(), cmd);
                return committed == null ? Result.failed(ErrorCode.GRAB_CONFLICT, null) : committed;
            }
            case REQUEST_ID_CONFLICT -> {
                return Result.failed(ErrorCode.DUPLICATE_REQUEST, null);
            }
            case NOT_GRABBABLE -> {
                return Result.failed(ErrorCode.ERRAND_NOT_GRABBABLE, null);
            }
            case ACQUIRED, SLOT_MISSING -> {
                // 名额键缺失时直接走 L4，不重建可能覆盖并发预占位的 Redis 快照。
            }
        }

        // L4：数据库 CAS 落库，最终裁决
        boolean reservedInRedis = outcome == SlotOutcome.ACQUIRED;
        try {
            Errand errand = errandRepository.findById(cmd.errandId()).orElse(null);
            if (errand == null) {
                rollbackIfReserved(cmd, reservedInRedis);
                return Result.failed(ErrorCode.ERRAND_NOT_FOUND, null);
            }

            int seq = errand.slotTaken() + 1;
            boolean ok = transactionalStep.lockAndRecord(
                    errand.campusId(), cmd.errandId(), cmd.runnerId(), errand.version(), seq, errand.round(),
                    idGenerator.nextId(), errand.status(), cmd.requestId());
            if (!ok) {
                Result committed = persistedResult(errand.campusId(), cmd);
                if (committed != null) return committed;
                rollbackIfReserved(cmd, reservedInRedis);
                return classifyUnavailable(cmd);
            }
            afterCommittedGrab(cmd, errand, reservedInRedis);
            return Result.success();
        } catch (RuntimeException e) {
            // 提交结果不明时先查数据库；只有确认未落库才撤销 Redis 预占位。
            try {
                Result committed = persistedResult(target.campusId(), cmd);
                if (committed != null) return committed;
                rollbackIfReserved(cmd, reservedInRedis);
            } catch (RuntimeException readFailure) {
                log.warn("抢单结果暂无法确认，保留 Redis 预占位待重试 errandId={}", cmd.errandId(), readFailure);
            }
            log.warn("抢单落库异常 errandId={} runnerId={}", cmd.errandId(), cmd.runnerId(), e);
            return Result.failed(ErrorCode.GRAB_CONFLICT, null);
        }
    }

    private Result persistedResult(long campusId, Command cmd) {
        var holder = grabRecordRepository.findRunnerByRequestId(campusId, cmd.errandId(), cmd.requestId());
        if (holder.isEmpty()) return null;
        return holder.get() == cmd.runnerId()
                ? Result.success() : Result.failed(ErrorCode.DUPLICATE_REQUEST, null);
    }

    private boolean mayHaveRecordedGrab(Errand errand) {
        // 首次发布且尚未锁定的任务不可能已有成功抢单记录，省掉热点首轮的一次 DB 查询。
        return errand.status() != ErrandStatus.PUBLISHED || errand.round() > 0;
    }

    private void rollbackIfReserved(Command cmd, boolean reserved) {
        if (!reserved) return;
        try {
            grabSlotPort.rollback(cmd.errandId(), cmd.runnerId(), cmd.requestId());
        } catch (RuntimeException e) {
            log.warn("Redis 名额补偿失败 errandId={} runnerId={}", cmd.errandId(), cmd.runnerId(), e);
        }
    }

    private Result classifyUnavailable(Command cmd) {
        Errand current = errandRepository.findById(cmd.errandId()).orElse(null);
        if (current == null) return Result.failed(ErrorCode.ERRAND_NOT_FOUND, null);
        if (current.grabberId() != null && current.grabberId() == cmd.runnerId()) {
            return Result.failed(ErrorCode.ALREADY_GRABBED, null);
        }
        if (current.status() == ErrandStatus.LOCKED) {
            try {
                return Result.failed(ErrorCode.SLOT_FULL, enqueueCandidate(cmd));
            } catch (RuntimeException e) {
                log.warn("候选排队失败 errandId={} runnerId={}", cmd.errandId(), cmd.runnerId(), e);
                return Result.failed(ErrorCode.SLOT_FULL, null);
            }
        }
        return Result.failed(current.status() == ErrandStatus.PUBLISHED
                ? ErrorCode.GRAB_CONFLICT : ErrorCode.ERRAND_NOT_GRABBABLE, null);
    }

    private void afterCommittedGrab(Command cmd, Errand previous, boolean reservedInRedis) {
        if (!reservedInRedis) {
            try {
                // DB 兜底抢中后丢弃可能陈旧的 Redis 名额，避免后续请求继续预占位。
                grabSlotPort.invalidate(cmd.errandId());
            } catch (RuntimeException e) {
                log.warn("抢单已提交，Redis 名额快照失效失败 errandId={}", cmd.errandId(), e);
            }
        }
        try {
            timeoutTransferUseCase.scheduleFirstTimeout(cmd.errandId(), previous.round(), previous.version() + 1);
        } catch (RuntimeException e) {
            // Worker 的超时扫描仍可兜底；数据库抢中事实不能因此被误报为失败。
            log.warn("抢单已提交，首轮超时消息登记失败 errandId={}", cmd.errandId(), e);
        }
        try {
            cacheEvict.evictAfterCommit(cmd.errandId());
        } catch (RuntimeException e) {
            log.warn("抢单已提交，详情缓存失效失败 errandId={}", cmd.errandId(), e);
        }
        try {
            Errand current = errandRepository.findById(cmd.errandId()).orElse(null);
            if (current != null) {
                notifier.errandStatusChanged(current.id(), current.publisherId(), current.grabberId(),
                        current.status().name(), current.round());
            }
        } catch (RuntimeException e) {
            log.warn("抢单已提交，实时通知失败 errandId={}", cmd.errandId(), e);
        }
    }

    /**
     * 抢单失败者进候选队列，为 P2 的超时流转做准备。
     * score 越小越优先：用时间戳减去信用分加权，让高信用用户略微占先，
     * 加权上限避免高信用用户完全垄断流转机会。
     */
    private Long enqueueCandidate(Command cmd) {
        // 信用分权重从后端读（入口处已查过一次，这里再查是为拿最新值——
        // 单主键查询成本极低，换来的是不依赖入口快照的正确性）
        int credit = creditRepository.scoreOf(cmd.runnerId());
        double creditBonus = Math.min(credit, 100) * 10.0;
        double score = System.currentTimeMillis() - creditBonus;
        candidateQueue.offer(cmd.errandId(), cmd.runnerId(), score);
        return candidateQueue.size(cmd.errandId());
    }
}
