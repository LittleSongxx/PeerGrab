package com.peergrab.worker;

import com.peergrab.infrastructure.persistence.JdbcFundEventOutboxRepository;
import com.peergrab.shared.MessagePayloadCodec;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

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

    public FundEventOutboxJob(JdbcFundEventOutboxRepository outbox,
                              ClientServiceProvider provider,
                              Producer producer,
                              @Value("${peergrab.mq.topic.fund-event:errand-fund-event-v2}") String topic) {
        this.outbox = outbox;
        this.provider = provider;
        this.producer = producer;
        this.topic = topic;
    }

    @Scheduled(fixedDelayString = "${peergrab.fund-outbox.retry-interval-ms:2000}", scheduler = "fastTaskScheduler")
    public void dispatch() {
        for (var claim : outbox.claimPending(BATCH_LIMIT)) {
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
    }

    static String payload(JdbcFundEventOutboxRepository.PendingEvent event) {
        return MessagePayloadCodec.fundEvent(event.bizNo(), event.type(), event.errandId(),
                event.publisherId(), event.runnerId(), event.amountCents(), event.commissionCents());
    }
}
