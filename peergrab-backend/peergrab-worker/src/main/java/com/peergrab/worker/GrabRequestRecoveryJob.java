package com.peergrab.worker;

import com.peergrab.application.usecase.GrabRequestRecoveryUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 抢单请求恢复扫描。
 *
 * <p>API 进程崩溃、Redis 预占后落库前宕机、响应丢失等场景会留下 PROCESSING 请求行。
 * 这里按租约过期时间收敛：能证明已提交的补成 SUCCEEDED，否则标 EXPIRED 并清理陈旧占位。
 * 恢复逻辑完全以数据库为入口，不扫 Redis key，避免"Redis 里有什么"成为事实来源。</p>
 */
@Component
@ConditionalOnProperty(name = "peergrab.grab-request.recovery-enabled", havingValue = "true", matchIfMissing = true)
public class GrabRequestRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(GrabRequestRecoveryJob.class);

    private final GrabRequestRecoveryUseCase recoveryUseCase;
    private final int batchSize;

    public GrabRequestRecoveryJob(GrabRequestRecoveryUseCase recoveryUseCase,
                                  @Value("${peergrab.grab-request.recovery-batch:200}") int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("恢复批次数必须为正");
        }
        this.recoveryUseCase = recoveryUseCase;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${peergrab.grab-request.recovery-interval-ms:10000}",
            scheduler = "fastTaskScheduler")
    public void run() {
        try {
            recoveryUseCase.expireBatch(batchSize);
        } catch (RuntimeException e) {
            log.warn("抢单请求恢复扫描失败，下一轮重试", e);
        }
    }
}
