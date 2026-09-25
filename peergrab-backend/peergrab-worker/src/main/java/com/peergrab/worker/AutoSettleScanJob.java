package com.peergrab.worker;

import com.peergrab.application.usecase.SettleErrandUseCase;
import com.peergrab.domain.errand.model.Errand;
import com.peergrab.domain.errand.ports.ErrandRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

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

    private final ErrandRepository errandRepository;
    private final SettleErrandUseCase settleUseCase;
    private final long autoSettleSeconds;
    private Instant afterAt;
    private long afterId;

    public AutoSettleScanJob(ErrandRepository errandRepository,
                             SettleErrandUseCase settleUseCase,
                             @Value("${peergrab.settle.auto-settle-seconds:86400}") long autoSettleSeconds) {
        this.errandRepository = errandRepository;
        this.settleUseCase = settleUseCase;
        this.autoSettleSeconds = autoSettleSeconds;
    }

    @Scheduled(fixedDelayString = "${peergrab.settle.scan-interval-ms:5000}", scheduler = "fastTaskScheduler")
    public void scan() {
        List<Errand> due = errandRepository.findAutoSettleDueAfter(autoSettleSeconds, afterAt, afterId, 200);
        if (due.isEmpty() && afterAt != null) {
            afterAt = null;
            afterId = 0;
            due = errandRepository.findAutoSettleDueAfter(autoSettleSeconds, null, 0, 200);
        }
        for (Errand e : due) {
            try {
                settleUseCase.settle(e.id(), Errand.SYSTEM_OPERATOR);
            } catch (Exception ex) {
                log.warn("自动结算兜底失败 errandId={}", e.id(), ex);
            }
        }
        if (!due.isEmpty()) {
            Errand last = due.get(due.size() - 1);
            afterAt = last.deliveredAt();
            afterId = last.id();
        }
        if (!due.isEmpty()) {
            log.info("自动结算兜底扫描处理 {} 条", due.size());
        }
    }
}
