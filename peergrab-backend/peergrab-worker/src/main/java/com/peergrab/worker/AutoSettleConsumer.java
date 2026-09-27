package com.peergrab.worker;

import com.peergrab.application.usecase.SettleErrandUseCase;
import com.peergrab.domain.errand.model.Errand;
import com.peergrab.shared.MessagePayloadCodec;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * 自动结算消费者：送达后 24h 未确认，定时消息到期触发。
 *
 * 与超时流转消费者同一个形态：主通道（MQ 定时消息）+ 兜底（AutoSettleScanJob）。
 * 幂等完全交给 SettleErrandUseCase 的三道闸门，
 * 主通道与兜底同时到达也只会结算一次。
 */
@Component
@ConditionalOnProperty(name = "peergrab.mq.enabled", havingValue = "true")
public class AutoSettleConsumer {

    private static final Logger log = LoggerFactory.getLogger(AutoSettleConsumer.class);

    private final ClientServiceProvider provider;
    private final ClientConfiguration configuration;
    private final SettleErrandUseCase settleUseCase;
    private final String topic;
    private final String group;

    private PushConsumer consumer;

    public AutoSettleConsumer(ClientServiceProvider provider,
                              ClientConfiguration configuration,
                              SettleErrandUseCase settleUseCase,
                              @Value("${peergrab.mq.topic.auto-settle:errand-auto-settle}") String topic,
                              @Value("${peergrab.mq.group.auto-settle:peergrab-autosettle-consumer}") String group) {
        this.provider = provider;
        this.configuration = configuration;
        this.settleUseCase = settleUseCase;
        this.topic = topic;
        this.group = group;
    }

    @Scheduled(fixedDelayString = "${peergrab.mq.consumer-reconnect-ms:10000}", scheduler = "mqConsumerScheduler")
    public synchronized void ensureStarted() {
        if (consumer != null) return;
        try {
            start();
        } catch (Exception e) {
            log.warn("自动结算消费者启动失败，稍后重试 topic={}", topic, e);
        }
    }

    public void start() throws Exception {
        consumer = provider.newPushConsumerBuilder()
                .setClientConfiguration(configuration)
                .setConsumerGroup(group)
                .setSubscriptionExpressions(Collections.singletonMap(
                        topic, new FilterExpression("*", FilterExpressionType.TAG)))
                .setMessageListener(msg -> {
                    String body = StandardCharsets.UTF_8.decode(msg.getBody()).toString();
                    long errandId;
                    try {
                        errandId = MessagePayloadCodec.readAutoSettle(body).errandId();
                    } catch (IllegalArgumentException e) {
                        log.error("自动结算毒消息，丢弃 body={}", body, e);
                        return ConsumeResult.SUCCESS;
                    }
                    try {
                        var result = settleUseCase.settle(errandId, Errand.SYSTEM_OPERATOR);
                        log.info("自动结算消息处理 errandId={} result={}", errandId, result);
                        // ALREADY_SETTLED / CONFLICT 也是幂等生效的正常结果，都要 ACK
                        return ConsumeResult.SUCCESS;
                    } catch (RuntimeException e) {
                        log.error("自动结算消息处理失败，交由 MQ 重试 body={}", body, e);
                        return ConsumeResult.FAILURE;
                    }
                })
                .build();
        log.info("自动结算消费者已启动 topic={} group={}", topic, group);
    }

    @PreDestroy
    public synchronized void stop() throws Exception {
        if (consumer != null) {
            consumer.close();
        }
    }

}
