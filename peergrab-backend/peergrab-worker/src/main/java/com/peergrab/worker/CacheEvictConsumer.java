package com.peergrab.worker;

import com.peergrab.domain.errand.ports.CacheEvictDelayPort;
import com.peergrab.domain.errand.ports.ErrandCachePort;
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
 * 延迟双删消费者：第二次删除的执行者。
 *
 * 写用例提交后：第一次删除立即执行（afterCommit），
 * 第二次删除延迟 500ms 由本消费者执行——覆盖"慢读在第一次删除后写回旧值"的窗口。
 *
 * 幂等：删除本身幂等（删不存在的 key 无副作用），重复消费无影响。
 */
@Component
@ConditionalOnProperty(name = "peergrab.mq.enabled", havingValue = "true")
public class CacheEvictConsumer {

    private static final Logger log = LoggerFactory.getLogger(CacheEvictConsumer.class);

    private final ClientServiceProvider provider;
    private final ClientConfiguration configuration;
    private final ErrandCachePort cache;
    private final String group;

    private PushConsumer consumer;

    public CacheEvictConsumer(ClientServiceProvider provider,
                              ClientConfiguration configuration,
                              ErrandCachePort cache,
                              @Value("${peergrab.mq.group.cache-evict:peergrab-cache-evict-consumer}") String group) {
        this.provider = provider;
        this.configuration = configuration;
        this.cache = cache;
        this.group = group;
    }

    @Scheduled(fixedDelayString = "${peergrab.mq.consumer-reconnect-ms:10000}", scheduler = "mqConsumerScheduler")
    public synchronized void ensureStarted() {
        if (consumer != null) return;
        try {
            start();
        } catch (Exception e) {
            log.warn("缓存延迟删除消费者启动失败，稍后重试", e);
        }
    }

    public void start() throws Exception {
        consumer = provider.newPushConsumerBuilder()
                .setClientConfiguration(configuration)
                .setConsumerGroup(group)
                .setSubscriptionExpressions(Collections.singletonMap(
                        CacheEvictDelayPort.TOPIC_CACHE_EVICT,
                        new FilterExpression("*", FilterExpressionType.TAG)))
                .setMessageListener(msg -> {
                    String body = StandardCharsets.UTF_8.decode(msg.getBody()).toString();
                    long errandId;
                    try {
                        errandId = MessagePayloadCodec.readCacheEvict(body).errandId();
                    } catch (IllegalArgumentException e) { // NumberFormatException 是其子类
                        // 只有消息格式错误才能丢弃；Redis 删除的任何异常都必须重试。
                        log.error("延迟双删毒消息，丢弃 body={}", body, e);
                        return ConsumeResult.SUCCESS;
                    }
                    try {
                        cache.evict(errandId);
                        log.debug("延迟双删第二次删除完成 errandId={}", errandId);
                        return ConsumeResult.SUCCESS;
                    } catch (RuntimeException e) {
                        log.error("延迟双删消费失败 body={}", body, e);
                        return ConsumeResult.FAILURE;
                    }
                })
                .build();
        log.info("延迟双删消费者已启动 topic={} group={}", CacheEvictDelayPort.TOPIC_CACHE_EVICT, group);
    }

    @PreDestroy
    public synchronized void stop() throws Exception {
        if (consumer != null) {
            consumer.close();
        }
    }

}
