package com.peergrab.worker;

import com.peergrab.application.usecase.TimeoutTransferUseCase;
import com.peergrab.domain.errand.model.Errand;
import com.peergrab.domain.errand.model.ErrandStatus;
import com.peergrab.domain.errand.ports.ErrandRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.concurrent.TimeUnit;

/**
 * 超时流转的兜底扫描——"主通道 + 兜底"模式的兜底侧。
 *
 * 主通道是 RocketMQ 定时消息，性能好、实时性强。但消息可能因为极端情况丢失
 * （Broker 磁盘故障、消息被误删、发送时应用崩溃），而"超时不流转"意味着
 * 任务永久卡在 LOCKED、资金永久冻结——这是不能接受的。
 *
 * 所以这里每 5 秒扫一次"超时且宽限期已过"的任务补偿处理。
 * 宽限期（grace）让主通道先处理，避免两边重复干活；即使重复了，
 * 幂等三件套也保证不会流转两次。
 * 单次调度连续处理有界数量的分页，避免大批任务同一时刻到期时每 200 条
 * 都额外等待一个调度间隔；页数和新到期扫描的运行时长双重限制保护同池其他定时任务。
 * 失败重试仍按单轮最多 50 条处理，不计入以下新到期扫描预算。
 */
@Component
@ConditionalOnProperty(name = "peergrab.timeout.scan.enabled", havingValue = "true", matchIfMissing = true)
public class TimeoutScanJob {

    private static final Logger log = LoggerFactory.getLogger(TimeoutScanJob.class);
    private static final int BATCH_LIMIT = 200;
    private static final int RETRY_LIMIT = 50;

    private final ErrandRepository errandRepository;
    private final TimeoutTransferUseCase timeoutTransferUseCase;
    private final ScanRetryQueue retryQueue;
    private final long graceSeconds;
    private final int maxBatches;
    private final long maxRunNanos;
    private final LongSupplier nanoTime;
    private Instant afterAt;
    private long afterId;

    @Autowired
    public TimeoutScanJob(ErrandRepository errandRepository,
                          TimeoutTransferUseCase timeoutTransferUseCase,
                          ScanRetryQueue retryQueue,
                          @Value("${peergrab.timeout.scan-grace-seconds:2}") long graceSeconds,
                          @Value("${peergrab.timeout.scan-max-batches:5}") int maxBatches,
                          @Value("${peergrab.timeout.scan-max-run-ms:10000}") long maxRunMs) {
        this(errandRepository, timeoutTransferUseCase, retryQueue,
                graceSeconds, maxBatches, maxRunMs, System::nanoTime);
    }

    TimeoutScanJob(ErrandRepository errandRepository,
                   TimeoutTransferUseCase timeoutTransferUseCase,
                   ScanRetryQueue retryQueue,
                   long graceSeconds,
                   int maxBatches,
                   long maxRunMs,
                   LongSupplier nanoTime) {
        if (maxBatches < 1 || maxRunMs < 1) {
            throw new IllegalArgumentException("timeout scan limits must be positive");
        }
        this.errandRepository = errandRepository;
        this.timeoutTransferUseCase = timeoutTransferUseCase;
        this.retryQueue = retryQueue;
        this.graceSeconds = graceSeconds;
        this.maxBatches = maxBatches;
        this.maxRunNanos = TimeUnit.MILLISECONDS.toNanos(maxRunMs);
        this.nanoTime = nanoTime;
    }

    @Scheduled(fixedDelayString = "${peergrab.timeout.scan-interval-ms:5000}", scheduler = "fastTaskScheduler")
    public void scan() {
        retryFailed();
        long startedAt = nanoTime.getAsLong();
        int batches = 0;
        int fetched = 0;
        int transferred = 0;
        int reverted = 0;
        int skipped = 0;
        boolean stop = false;
        while (batches < maxBatches && !timeBudgetExhausted(startedAt)) {
            List<Errand> candidates = errandRepository.findConfirmTimeoutAfter(
                    graceSeconds, afterAt, afterId, BATCH_LIMIT);
            if (candidates.isEmpty() && afterAt != null) {
                resetCursor();
                // Earlier rows may still be due (for example a leased candidate).
                // Revisit them only at the start of a run, not twice in this run.
                if (batches == 0) {
                    candidates = errandRepository.findConfirmTimeoutAfter(
                            graceSeconds, null, 0, BATCH_LIMIT);
                }
            }
            if (candidates.isEmpty()) {
                break;
            }
            batches++;
            fetched += candidates.size();
            Map<Long, Integer> tracked = retryQueue.trackedRounds(ScanRetryQueue.Type.CONFIRM_TIMEOUT,
                    candidates.stream().map(Errand::id).toList());
            for (Errand errand : candidates) {
                if (timeBudgetExhausted(startedAt)) {
                    stop = true;
                    break;
                }
                if (Integer.valueOf(errand.round()).equals(tracked.get(errand.id()))) {
                    advance(errand);
                    continue;
                }
                try {
                    switch (timeoutTransferUseCase.handleTimeout(errand.id(), errand.round())) {
                        case TRANSFERRED -> transferred++;
                        case REVERTED -> reverted++;
                        case SKIPPED -> skipped++;
                    }
                } catch (RuntimeException e) {
                    log.warn("兜底流转失败 errandId={}", errand.id(), e);
                    try {
                        // 只有先落库，游标才可安全越过该失败项。
                        retryQueue.recordFailure(ScanRetryQueue.Type.CONFIRM_TIMEOUT,
                                errand.id(), errand.round(), e);
                    } catch (RuntimeException persistFailure) {
                        log.error("兜底流转失败项登记失败，停止推进游标 errandId={}",
                                errand.id(), persistFailure);
                        stop = true;
                        break;
                    }
                }
                advance(errand);
            }
            if (stop) {
                break;
            }
            if (candidates.size() < BATCH_LIMIT) {
                // No more than this page was due at query time. Keep the cursor;
                // the next scan wraps to the oldest still-due row when needed.
                break;
            }
        }
        if (fetched > 0) {
            log.info("兜底扫描完成 批次={} 捞取={} 流转={} 回退={} 跳过={}",
                    batches, fetched, transferred, reverted, skipped);
        }
    }

    private boolean timeBudgetExhausted(long startedAt) {
        return nanoTime.getAsLong() - startedAt >= maxRunNanos;
    }

    private void resetCursor() {
        afterAt = null;
        afterId = 0;
    }

    private void advance(Errand errand) {
        afterAt = errand.confirmDeadlineAt();
        afterId = errand.id();
    }

    private void retryFailed() {
        List<ScanRetryQueue.RetryItem> due;
        try {
            due = retryQueue.claimDue(ScanRetryQueue.Type.CONFIRM_TIMEOUT, RETRY_LIMIT);
        } catch (RuntimeException e) {
            log.error("读取超时流转重试队列失败，继续扫描新到期任务", e);
            return;
        }
        for (ScanRetryQueue.RetryItem item : due) {
            try {
                Errand current = errandRepository.findById(item.errandId()).orElse(null);
                if (current == null || current.status() != ErrandStatus.LOCKED
                        || current.round() != item.round()) {
                    retryQueue.complete(ScanRetryQueue.Type.CONFIRM_TIMEOUT, item);
                    continue;
                }
                timeoutTransferUseCase.handleTimeout(item.errandId(), item.round());
                retryQueue.complete(ScanRetryQueue.Type.CONFIRM_TIMEOUT, item);
            } catch (RuntimeException e) {
                log.warn("重试超时流转失败 errandId={} round={}", item.errandId(), item.round(), e);
                try {
                    retryQueue.reschedule(ScanRetryQueue.Type.CONFIRM_TIMEOUT, item, e);
                } catch (RuntimeException persistFailure) {
                    // 保留领取租约；进程退出或租约到期后仍可重试。
                    log.error("重排超时流转失败项失败 errandId={}", item.errandId(), persistFailure);
                }
            }
        }
    }
}
