package com.peergrab.worker;

import com.peergrab.application.usecase.SettleErrandUseCase;
import com.peergrab.domain.errand.model.Errand;
import com.peergrab.domain.errand.model.ErrandStatus;
import com.peergrab.domain.errand.ports.ErrandRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 自动结算兜底扫描：每 5 秒扫一次 DELIVERED 超过窗口的任务。
 *
 * 与超时流转的兜底扫描（TimeoutScanJob）是同一个模式：
 * 主通道是 MQ 定时消息追实时性，本 job 追不丢。靠 SettleErrandUseCase 的
 * 幂等三道闸门保证"主通道和兜底同时到达也不会重复打钱"。
 */
@Component
public class AutoSettleScanJob {

    private static final Logger log = LoggerFactory.getLogger(AutoSettleScanJob.class);
    private static final int BATCH_LIMIT = 200;
    private static final int RETRY_LIMIT = 50;

    private final ErrandRepository errandRepository;
    private final SettleErrandUseCase settleUseCase;
    private final ScanRetryQueue retryQueue;
    private final int maxBatches;
    private final long maxRunNanos;
    private final java.util.function.LongSupplier nanoTime;
    private final Timer scanTimer;
    private final Timer settleTimer;
    private final Timer batchSettleTimer;
    private final Counter batchCounter;
    private final Counter batchAttemptCounter;
    private final Counter batchSettledCounter;
    private final Counter batchSkippedCounter;
    private final Counter batchFallbackCounter;
    private final Counter processedCounter;
    private final Counter failureCounter;
    private final AtomicInteger lastPageSize = new AtomicInteger();
    private Instant afterAt;
    private long afterId;

    @Autowired
    public AutoSettleScanJob(ErrandRepository errandRepository,
                             SettleErrandUseCase settleUseCase,
                             ScanRetryQueue retryQueue,
                             MeterRegistry registry,
                             @Value("${peergrab.settle.scan-max-batches:5}") int maxBatches,
                             @Value("${peergrab.settle.scan-max-run-ms:30000}") long maxRunMs) {
        this(errandRepository, settleUseCase, retryQueue, registry,
                maxBatches, maxRunMs, System::nanoTime);
    }

    /** Lightweight constructor retained for unit tests and non-Spring callers. */
    AutoSettleScanJob(ErrandRepository errandRepository,
                      SettleErrandUseCase settleUseCase,
                      ScanRetryQueue retryQueue) {
        this(errandRepository, settleUseCase, retryQueue, new SimpleMeterRegistry(),
                5, 30_000, System::nanoTime);
    }

    AutoSettleScanJob(ErrandRepository errandRepository,
                      SettleErrandUseCase settleUseCase,
                      ScanRetryQueue retryQueue,
                      MeterRegistry registry,
                      int maxBatches,
                      long maxRunMs,
                      java.util.function.LongSupplier nanoTime) {
        if (maxBatches < 1 || maxRunMs < 1) {
            throw new IllegalArgumentException("auto-settle scan limits must be positive");
        }
        this.errandRepository = errandRepository;
        this.settleUseCase = settleUseCase;
        this.retryQueue = retryQueue;
        this.maxBatches = maxBatches;
        this.maxRunNanos = TimeUnit.MILLISECONDS.toNanos(maxRunMs);
        this.nanoTime = nanoTime;
        this.scanTimer = Timer.builder("peergrab.auto_settle.scan")
                .description("Duration of one automatic settlement fallback scan invocation")
                .serviceLevelObjectives(Duration.ofMillis(10), Duration.ofMillis(100),
                        Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(10),
                        Duration.ofSeconds(30), Duration.ofMinutes(1))
                .register(registry);
        this.settleTimer = Timer.builder("peergrab.auto_settle.scan.item")
                .description("Per-row automatic settlement attempt from the fallback scanner")
                .serviceLevelObjectives(Duration.ofMillis(10), Duration.ofMillis(50),
                        Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500),
                        Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(5))
                .register(registry);
        this.batchSettleTimer = Timer.builder("peergrab.auto_settle.scan.batch")
                .description("Duration of one transactional automatic-settlement batch")
                .serviceLevelObjectives(Duration.ofMillis(10), Duration.ofMillis(100),
                        Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(10),
                        Duration.ofSeconds(30))
                .register(registry);
        this.batchCounter = Counter.builder("peergrab.auto_settle.scan.batches")
                .description("Auto-settle fallback pages fetched")
                .register(registry);
        this.batchAttemptCounter = Counter.builder("peergrab.auto_settle.scan.batch.attempts")
                .description("Transactional automatic-settlement batches attempted")
                .register(registry);
        this.batchSettledCounter = Counter.builder("peergrab.auto_settle.scan.batch.settled")
                .description("Rows settled by the transactional automatic-settlement batch path")
                .register(registry);
        this.batchSkippedCounter = Counter.builder("peergrab.auto_settle.scan.batch.skipped")
                .description("Rows skipped by the transactional automatic-settlement batch path")
                .register(registry);
        this.batchFallbackCounter = Counter.builder("peergrab.auto_settle.scan.batch.fallback")
                .description("Batches that fell back to single-row settlement after rollback")
                .register(registry);
        this.processedCounter = Counter.builder("peergrab.auto_settle.scan.processed")
                .description("Auto-settle fallback rows attempted")
                .register(registry);
        this.failureCounter = Counter.builder("peergrab.auto_settle.scan.failures")
                .description("Auto-settle fallback rows that entered durable retry")
                .register(registry);
        Gauge.builder("peergrab.auto_settle.scan.last_page_size", lastPageSize, AtomicInteger::get)
                .description("Rows returned by the last auto-settle fallback page")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${peergrab.settle.scan-interval-ms:5000}", scheduler = "fastTaskScheduler")
    public void scan() {
        long started = nanoTime.getAsLong();
        try {
            retryFailed();
            int batches = 0;
            int fetched = 0;
            int processed = 0;
            AtomicInteger failures = new AtomicInteger();
            boolean stop = false;
            while (batches < maxBatches && !timeBudgetExhausted(started)) {
                List<Errand> due = errandRepository.findAutoSettleDueAfter(0, afterAt, afterId, BATCH_LIMIT);
                if (due.isEmpty() && afterAt != null) {
                    resetCursor();
                    // At the beginning of a run, revisit the oldest due page. This
                    // avoids starving rows inserted behind a previous cursor while
                    // keeping one invocation from scanning the same page twice.
                    if (batches == 0) {
                        due = errandRepository.findAutoSettleDueAfter(0, null, 0, BATCH_LIMIT);
                    }
                }
                if (due.isEmpty()) break;
                batches++;
                batchCounter.increment();
                fetched += due.size();
                lastPageSize.set(due.size());
                Map<Long, Integer> tracked = retryQueue.trackedRounds(ScanRetryQueue.Type.AUTO_SETTLE,
                        due.stream().map(Errand::id).toList());
                List<Errand> candidates = due.stream()
                        .filter(e -> !Integer.valueOf(0).equals(tracked.get(e.id())))
                        .toList();
                processed += candidates.size();
                processedCounter.increment(candidates.size());

                if (!candidates.isEmpty()) {
                    batchAttemptCounter.increment();
                    long batchStarted = nanoTime.getAsLong();
                    try {
                        SettleErrandUseCase.BatchResult result = settleUseCase.settleAutoBatch(candidates);
                        batchSettledCounter.increment(result.settled());
                        batchSkippedCounter.increment(result.skipped());
                        batchSettleTimer.record(nanoTime.getAsLong() - batchStarted, TimeUnit.NANOSECONDS);
                        // The batch transaction has committed. Advance past all rows,
                        // including entries already claimed by the durable retry queue.
                        for (Errand e : due) advance(e);
                    } catch (Exception batchFailure) {
                        batchFallbackCounter.increment();
                        batchSettleTimer.record(nanoTime.getAsLong() - batchStarted, TimeUnit.NANOSECONDS);
                        log.warn("自动结算批次失败，回退逐条重试 batchSize={}", candidates.size(), batchFailure);
                        // A failed batch is rolled back as a whole. Retry each row
                        // independently so one poison row cannot block later due work.
                        for (Errand e : due) {
                            if (timeBudgetExhausted(started)) {
                                stop = true;
                                break;
                            }
                            if (Integer.valueOf(0).equals(tracked.get(e.id()))) {
                                advance(e);
                                continue;
                            }
                            if (!settleOneWithRetry(e, failures)) {
                                stop = true;
                                break;
                            }
                        }
                    }
                } else {
                    // Every row is already represented in the durable retry queue;
                    // it is safe to move the cursor without opening a new transaction.
                    for (Errand e : due) advance(e);
                }
                if (stop || due.size() < BATCH_LIMIT) break;
            }
            if (fetched > 0) {
                log.info("自动结算兜底扫描处理 批次={} 捞取={} 尝试={} 失败={}",
                        batches, fetched, processed, failures.get());
            }
        } finally {
            scanTimer.record(nanoTime.getAsLong() - started, TimeUnit.NANOSECONDS);
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
        afterAt = errand.autoSettleDeadlineAt();
        afterId = errand.id();
    }

    /**
     * Fallback for a batch transaction that rolled back. Returns false only when
     * the durable retry row itself could not be recorded; in that case the cursor
     * deliberately remains before the failed item so the next scan cannot skip it.
     */
    private boolean settleOneWithRetry(Errand errand, AtomicInteger failures) {
        long settleStarted = nanoTime.getAsLong();
        try {
            settleUseCase.settle(errand.id(), Errand.SYSTEM_OPERATOR);
        } catch (Exception ex) {
            failures.incrementAndGet();
            failureCounter.increment();
            log.warn("自动结算兜底失败 errandId={}", errand.id(), ex);
            try {
                retryQueue.recordFailure(ScanRetryQueue.Type.AUTO_SETTLE, errand.id(), 0, ex);
            } catch (RuntimeException persistFailure) {
                log.error("自动结算失败项登记失败，停止推进游标 errandId={}", errand.id(), persistFailure);
                return false;
            }
        } finally {
            settleTimer.record(nanoTime.getAsLong() - settleStarted, TimeUnit.NANOSECONDS);
        }
        advance(errand);
        return true;
    }

    private void retryFailed() {
        List<ScanRetryQueue.RetryItem> due;
        try {
            due = retryQueue.claimDue(ScanRetryQueue.Type.AUTO_SETTLE, RETRY_LIMIT);
        } catch (RuntimeException e) {
            log.error("读取自动结算重试队列失败，继续扫描新到期任务", e);
            return;
        }
        for (ScanRetryQueue.RetryItem item : due) {
            try {
                Errand current = errandRepository.findById(item.errandId()).orElse(null);
                if (current == null || current.status() != ErrandStatus.DELIVERED) {
                    retryQueue.complete(ScanRetryQueue.Type.AUTO_SETTLE, item);
                    continue;
                }
                settleUseCase.settle(item.errandId(), Errand.SYSTEM_OPERATOR);
                retryQueue.complete(ScanRetryQueue.Type.AUTO_SETTLE, item);
            } catch (Exception e) {
                log.warn("重试自动结算失败 errandId={}", item.errandId(), e);
                try {
                    retryQueue.reschedule(ScanRetryQueue.Type.AUTO_SETTLE, item, e);
                } catch (RuntimeException persistFailure) {
                    log.error("重排自动结算失败项失败 errandId={}", item.errandId(), persistFailure);
                }
            }
        }
    }
}
