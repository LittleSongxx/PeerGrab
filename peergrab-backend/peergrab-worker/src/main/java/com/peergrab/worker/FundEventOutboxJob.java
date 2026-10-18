package com.peergrab.worker;

import com.peergrab.infrastructure.persistence.JdbcFundEventOutboxRepository;
import com.peergrab.shared.MessagePayloadCodec;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.LongSupplier;

/** MQ 不可用时只积压 outbox；恢复后继续投递，资金操作本身不等待 MQ。 */
@Component
@ConditionalOnProperty(name = "peergrab.mq.enabled", havingValue = "true")
public class FundEventOutboxJob {

    private static final Logger log = LoggerFactory.getLogger(FundEventOutboxJob.class);
    private static final int BATCH_LIMIT = 100;

    private final JdbcFundEventOutboxRepository outbox;
    private final ClientServiceProvider provider;
    private final Producer producer;
    private final String topic;
    private final long maxRunNanos;
    private final LongSupplier nanoTime;

    /** 测试便捷构造（默认 10 秒预算）。 */
    public FundEventOutboxJob(JdbcFundEventOutboxRepository outbox,
                              ClientServiceProvider provider,
                              Producer producer,
                              String topic) {
        this(outbox, provider, producer, topic, 10_000L);
    }

    @Autowired
    public FundEventOutboxJob(JdbcFundEventOutboxRepository outbox,
                              ClientServiceProvider provider,
                              Producer producer,
                              @Value("${peergrab.mq.topic.fund-event:errand-fund-event-v2}") String topic,
                              @Value("${peergrab.fund-outbox.max-run-ms:10000}") long maxRunMs) {
        this(outbox, provider, producer, topic, maxRunMs, System::nanoTime);
    }

    FundEventOutboxJob(JdbcFundEventOutboxRepository outbox,
                       ClientServiceProvider provider, Producer producer,
                       String topic, long maxRunMs, LongSupplier nanoTime) {
        this.outbox = outbox;
        this.provider = provider;
        this.producer = producer;
        this.topic = topic;
        this.maxRunNanos = Math.max(1, maxRunMs) * 1_000_000L;
        this.nanoTime = nanoTime;
    }

    @Scheduled(fixedDelayString = "${peergrab.fund-outbox.retry-interval-ms:2000}", scheduler = "mqSendScheduler")
    public void dispatch() {
        List<JdbcFundEventOutboxRepository.ClaimedEvent> pending = outbox.claimPending(BATCH_LIMIT);
        long started = nanoTime.getAsLong();
        int processed = 0;
        try {
            for (var claim : pending) {
                if (nanoTime.getAsLong() - started >= maxRunNanos) {
                    log.warn("资金事件投递达到单轮时间预算，剩余 {} 条留待下一轮",
                            pending.size() - processed);
                    break;
                }
                processed++;
                var event = claim.event();
                try {
                    var message = provider.newMessageBuilder()
                            .setTopic(topic)
                            .setKeys(event.bizNo())
                            .setTag(event.type())
                            .setBody(payload(event).getBytes(StandardCharsets.UTF_8))
                            .build();
                    producer.send(message);
                    if (!outbox.markClaimedSent(event.bizNo(), claim.claimToken())) {
                        log.warn("资金事件发送成功但领取已失效，可能重复投递 bizNo={}", event.bizNo());
                    }
                } catch (Exception e) {
                    try {
                        outbox.markClaimedRetry(event.bizNo(), claim.claimToken());
                    } catch (RuntimeException dbError) {
                        e.addSuppressed(dbError);
                    }
                    log.warn("资金事件投递失败，将重试 bizNo={} retry={}", event.bizNo(), event.retryCount(), e);
                }
            }
        } finally {
            releaseUnprocessedClaims(pending, processed);
        }
    }

    /** 预算提前结束/异常退出时释放未处理事件的租约，避免空占 120 秒。 */
    private void releaseUnprocessedClaims(List<JdbcFundEventOutboxRepository.ClaimedEvent> pending,
                                          int processed) {
        for (int index = processed; index < pending.size(); index++) {
            var claim = pending.get(index);
            try {
                outbox.releaseClaim(claim.event().bizNo(), claim.claimToken());
            } catch (RuntimeException releaseFailure) {
                log.warn("释放未处理资金事件租约失败 bizNo={}", claim.event().bizNo(), releaseFailure);
            }
        }
    }

    static String payload(JdbcFundEventOutboxRepository.PendingEvent event) {
        return MessagePayloadCodec.fundEvent(event.bizNo(), event.type(), event.errandId(),
                event.publisherId(), event.runnerId(), event.amountCents(), event.commissionCents());
    }
}
