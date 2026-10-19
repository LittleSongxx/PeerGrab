package com.peergrab.lab.txmsg;

/**
 * 半消息在 Broker 侧的生命周期状态。
 *
 * <pre>
 * beginTransaction
 *        |
 *        v
 *     PREPARED ──commit──> COMMITTED（消费者可见，只投递一次）
 *        │
 *        ├──rollback──> ROLLED_BACK（消息丢弃）
 *        │
 *        └──check-back 超宽限仍 UNKNOWN──> DISCARDED（Broker 主动丢弃）
 * </pre>
 *
 * <p>对应真实 RocketMQ 中半消息的“已准备 / 已提交 / 已回滚 / 已丢弃”几种结局；
 * 真实客户端只暴露 Transaction 的 commit/rollback，DISCARDED 是 Broker 回查耗尽后的
 * 内部结果，这里显式建模是为了让测试能区分“业务主动回滚”和“回查超时丢弃”。
 */
public enum TransactionMessageStatus {
    PREPARED,
    COMMITTED,
    ROLLED_BACK,
    DISCARDED
}
