package com.peergrab.lab.txmsg;

/**
 * Broker 事务回查器：本地事务结果未知时，根据持久化事实给出三态结论。
 *
 * <p>对应 RocketMQ 5.x gRPC 客户端的
 * {@code org.apache.rocketmq.client.apis.producer.TransactionChecker}；
 * 真实接口收到的是 {@code Transaction}，lab 用 {@link TransactionMessage} 承载回查所需的
 * bizNo。实现必须满足两条纪律：
 * <ol>
 *   <li><b>只查只增不减的账本事实</b>：例如 {@code wallet_ledger} 中是否已有该 bizNo 的流水。
 *       流水一旦插入不会再被改写，才是“本地事务成功”的可靠证据。</li>
 *   <li><b>不查可变业务状态</b>：例如 {@code errand.status} 会被超时、改派、重试改写，
 *       拿它当判据可能在事务已成功时返回 ROLLBACK，造成资金事实与消息永久不一致。</li>
 * </ol>
 *
 * <p>实现是无状态的纯函数，便于在测试中注入固定的账本事实集合。
 */
@FunctionalInterface
public interface TransactionChecker {

    /**
     * @param halfMessage Broker 保存的半消息快照；实现只应使用 bizNo 等不可变字段
     * @return COMMIT / ROLLBACK / UNKNOWN，不能返回 {@code null}
     */
    TransactionResolution check(TransactionMessage halfMessage);
}
