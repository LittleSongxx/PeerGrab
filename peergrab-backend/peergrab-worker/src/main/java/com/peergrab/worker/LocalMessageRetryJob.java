package com.peergrab.worker;

import com.peergrab.domain.errand.ports.DelayMessagePort;
import com.peergrab.domain.errand.ports.LocalMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.LongSupplier;

/**
 * 本地消息表重发 job。
 *
 * 本地消息表方案的完整性就靠这个 job：业务事务里登记了 PENDING 消息，
 * 但发送那一刻可能失败（MQ 不可用、网络抖动、应用崩溃）。
 * 有了它，"业务成功但消息丢了"这种黑洞就不存在了——
 * 消息状态是数据库里可查询的事实，不是靠祈祷。
 */
@Component
public class LocalMessageRetryJob {

    private static final Logger log = LoggerFactory.getLogger(LocalMessageRetryJob.class);
    private static final int BATCH_LIMIT = 100;
    // MQ 故障可能持续超过一分钟；退避到 5 分钟后继续尝试，长期毒消息才转 DEAD。
    private static final int MAX_RETRY = 1000;

    private final LocalMessageRepository localMessageRepository;
    private final DelayMessagePort delayMessagePort;
    private final long maxRunNanos;
    private final LongSupplier nanoTime;

    public LocalMessageRetryJob(LocalMessageRepository localMessageRepository,
                                DelayMessagePort delayMessagePort) {
        this(localMessageRepository, delayMessagePort, 10_000L);
    }

    @Autowired
    public LocalMessageRetryJob(LocalMessageRepository localMessageRepository,
                                DelayMessagePort delayMessagePort,
                                @Value("${peergrab.message.retry-max-run-ms:10000}") long maxRunMs) {
        this(localMessageRepository, delayMessagePort, maxRunMs, System::nanoTime);
    }

    LocalMessageRetryJob(LocalMessageRepository localMessageRepository,
                         DelayMessagePort delayMessagePort, long maxRunMs, LongSupplier nanoTime) {
        this.localMessageRepository = localMessageRepository;
        this.delayMessagePort = delayMessagePort;
        this.maxRunNanos = Math.max(1, maxRunMs) * 1_000_000L;
        this.nanoTime = nanoTime;
    }

    @Scheduled(fixedDelayString = "${peergrab.message.retry-interval-ms:10000}", scheduler = "mqSendScheduler")
    public void retry() {
        if (!delayMessagePort.available()) {
            return;
        }
        List<LocalMessageRepository.ClaimedMessage> pending = localMessageRepository.claimPending(BATCH_LIMIT);
        if (pending.isEmpty()) {
            return;
        }
        long started = nanoTime.getAsLong();
        int processed = 0;
        int sent = 0;
        try {
            for (var claim : pending) {
                if (nanoTime.getAsLong() - started >= maxRunNanos) {
                    // 单轮时间预算：Broker 黑洞式故障时把剩余消息留到下一轮，
                    // 避免一个调度线程被 100 次 10 秒超时长期占住。
                    log.warn("消息重发达到单轮时间预算，剩余 {} 条留待下一轮",
                            pending.size() - processed);
                    break;
                }
                processed++;
                var msg = claim.message();
                try {
                    delayMessagePort.send(msg.topic(), msg.msgKey(), msg.payload(), msg.deliverAt());
                    if (!localMessageRepository.markClaimedSent(msg.msgKey(), claim.claimToken())) {
                        log.warn("发送成功但领取已失效，消息可能重复投递 msgKey={}", msg.msgKey());
                    }
                    sent++;
                } catch (RuntimeException e) {
                    // 指数退避后重试；next_retry_at 让坏消息不会占满每轮的前 100 条。
                    localMessageRepository.markClaimedRetry(msg.msgKey(), claim.claimToken(), MAX_RETRY);
                    log.warn("消息重发失败 msgKey={} retry={}", msg.msgKey(), msg.retryCount(), e);
                }
            }
        } finally {
            releaseUnprocessedClaims(pending, processed);
        }
        log.info("消息重发完成 扫描={} 成功={}", pending.size(), sent);
    }

    /** 预算提前结束/异常退出时释放未处理消息的租约，避免空占 120 秒。 */
    private void releaseUnprocessedClaims(List<LocalMessageRepository.ClaimedMessage> pending,
                                          int processed) {
        for (int index = processed; index < pending.size(); index++) {
            var claim = pending.get(index);
            try {
                localMessageRepository.releaseClaim(claim.message().msgKey(), claim.claimToken());
            } catch (RuntimeException releaseFailure) {
                log.warn("释放未处理消息租约失败 msgKey={}", claim.message().msgKey(), releaseFailure);
            }
        }
    }
}
