package com.peergrab.worker;

import com.peergrab.domain.errand.ports.DelayMessagePort;
import com.peergrab.domain.errand.ports.LocalMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

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

    public LocalMessageRetryJob(LocalMessageRepository localMessageRepository,
                                DelayMessagePort delayMessagePort) {
        this.localMessageRepository = localMessageRepository;
        this.delayMessagePort = delayMessagePort;
    }

    @Scheduled(fixedDelayString = "${peergrab.message.retry-interval-ms:10000}", scheduler = "fastTaskScheduler")
    public void retry() {
        List<LocalMessageRepository.PendingMessage> pending = localMessageRepository.findPending(BATCH_LIMIT);
        if (pending.isEmpty()) {
            return;
        }
        int sent = 0;
        for (var msg : pending) {
            try {
                delayMessagePort.send(msg.topic(), msg.msgKey(), msg.payload(), msg.deliverAt());
                localMessageRepository.markSent(msg.msgKey());
                sent++;
            } catch (RuntimeException e) {
                // 指数退避后重试；next_retry_at 让坏消息不会占满每轮的前 100 条。
                localMessageRepository.markRetry(msg.msgKey(), MAX_RETRY);
                log.warn("消息重发失败 msgKey={} retry={}", msg.msgKey(), msg.retryCount(), e);
            }
        }
        log.info("消息重发完成 扫描={} 成功={}", pending.size(), sent);
    }
}
