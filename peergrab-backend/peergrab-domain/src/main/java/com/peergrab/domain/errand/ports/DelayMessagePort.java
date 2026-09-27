package com.peergrab.domain.errand.ports;

import java.time.Instant;

/**
 * 延迟消息端口。
 *
 * 领域层只表达"我要在某个时刻收到一条消息"，完全不认识 RocketMQ。
 * 这样做的价值在教程第 11 章会体现：对比 DB 扫表 / Redis ZSET / 时间轮 /
 * RabbitMQ TTL+DLX / RocketMQ 定时消息五种实现时，只需换这个端口的适配器，
 * 业务代码一行不改。
 */
public interface DelayMessagePort {

    /** A disabled transport must leave local outbox rows pending for later replay. */
    default boolean available() { return true; }

    /**
     * 投递一条定时消息。
     *
     * @param msgKey    业务幂等与检索键，如 timeout:{errandId}:{round}；Broker 不保证按 key 去重
     * @param payload   消息体（JSON）
     * @param deliverAt 期望投递时间
     */
    void send(String topic, String msgKey, String payload, Instant deliverAt);
}
