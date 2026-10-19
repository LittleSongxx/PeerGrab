package com.peergrab.lab.txmsg;

/**
 * 事务回查的三态结论。
 *
 * <p>直接对应 RocketMQ 5.x gRPC 客户端的
 * {@code org.apache.rocketmq.client.apis.producer.TransactionResolution}：
 * <ul>
 *   <li>{@link #COMMIT}：本地事务确实成功，Broker 应提交半消息；</li>
 *   <li>{@link #ROLLBACK}：本地事务确定失败，Broker 应丢弃半消息；</li>
 *   <li>{@link #UNKNOWN}：暂时无法判断（本地事务仍在执行、流水尚未可见等），
 *       Broker 保留半消息并稍后重试回查。</li>
 * </ul>
 */
public enum TransactionResolution {
    COMMIT,
    ROLLBACK,
    UNKNOWN
}
