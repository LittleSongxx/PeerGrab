package com.peergrab.worker;

import com.peergrab.application.usecase.SettleErrandUseCase;
import com.peergrab.domain.errand.model.Errand;
import com.peergrab.domain.errand.model.ErrandStatus;
import com.peergrab.domain.errand.ports.ErrandRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
    private Instant afterAt;
    private long afterId;

    public AutoSettleScanJob(ErrandRepository errandRepository,
                             SettleErrandUseCase settleUseCase,
                             ScanRetryQueue retryQueue) {
        this.errandRepository = errandRepository;
        this.settleUseCase = settleUseCase;
        this.retryQueue = retryQueue;
    }

    @Scheduled(fixedDelayString = "${peergrab.settle.scan-interval-ms:5000}", scheduler = "fastTaskScheduler")
    public void scan() {
        retryFailed();
        List<Errand> due = errandRepository.findAutoSettleDueAfter(0, afterAt, afterId, BATCH_LIMIT);
        if (due.isEmpty() && afterAt != null) {
            afterAt = null;
            afterId = 0;
            due = errandRepository.findAutoSettleDueAfter(0, null, 0, BATCH_LIMIT);
        }
        Map<Long, Integer> tracked = retryQueue.trackedRounds(ScanRetryQueue.Type.AUTO_SETTLE,
                due.stream().map(Errand::id).toList());
        for (Errand e : due) {
            if (Integer.valueOf(0).equals(tracked.get(e.id()))) {
                advance(e);
                continue;
            }
            try {
                settleUseCase.settle(e.id(), Errand.SYSTEM_OPERATOR);
            } catch (Exception ex) {
                log.warn("自动结算兜底失败 errandId={}", e.id(), ex);
                try {
                    retryQueue.recordFailure(ScanRetryQueue.Type.AUTO_SETTLE, e.id(), 0, ex);
                } catch (RuntimeException persistFailure) {
                    log.error("自动结算失败项登记失败，停止推进游标 errandId={}", e.id(), persistFailure);
                    break;
                }
            }
            advance(e);
        }
        if (!due.isEmpty()) {
            log.info("自动结算兜底扫描处理 {} 条", due.size());
        }
    }

    private void advance(Errand errand) {
        afterAt = errand.autoSettleDeadlineAt();
        afterId = errand.id();
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
