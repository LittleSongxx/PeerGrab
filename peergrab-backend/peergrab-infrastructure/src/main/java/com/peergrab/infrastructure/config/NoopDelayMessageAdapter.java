package com.peergrab.infrastructure.config;

import com.peergrab.domain.errand.ports.DelayMessagePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 延迟消息端口的空实现：只登记不投递。
 *
 * 用途有两个：
 *   1. RocketMQ 未启用时（peergrab.mq.enabled=false）应用与集成测试仍能跑通，
 *      超时流转退化为"纯靠 worker 兜底扫描"——这恰好证明了兜底通道的价值：
 *      主通道完全不可用时，业务只是变慢，不会卡死。
 *   2. 教程第 11 章对比延迟队列五种方案时，换实现不换业务代码的示例。
 *
 * 消息仍然写进了 local_message（PENDING），所以没有信息丢失。
 */
@Component
@ConditionalOnProperty(name = "peergrab.mq.enabled", havingValue = "false", matchIfMissing = true)
public class NoopDelayMessageAdapter implements DelayMessagePort {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public void send(String topic, String msgKey, String payload, Instant deliverAt) {
        throw new IllegalStateException("MQ 未启用，定时消息须保留 PENDING: " + msgKey);
    }
}
