package com.peergrab.lab.txmsg;

import java.util.Objects;

/**
 * 一条事务消息的不可变快照。
 *
 * <p>对应 RocketMQ 5.x 中“半消息 + 事务状态”的组合：生产者先发送
 * {@link TransactionMessageStatus#PREPARED} 半消息，本地事务完成后由 Broker 推进到
 * COMMITTED / ROLLED_BACK；回查超过宽限仍无法确认时推进到 DISCARDED。
 *
 * <p>record 本身不可变，{@link #withStatus(TransactionMessageStatus)} 返回新快照，
 * 由 {@link InMemoryTransactionBroker} 负责替换，避免把可变状态暴露给回查器。
 *
 * @param bizNo   业务幂等号，也是账本回查的唯一查询键
 * @param type    业务类型（如 SETTLE、REFUND），用于路由与排障
 * @param payload 消息体；lab 用字符串模拟序列化后的事件
 * @param status  Broker 侧状态
 */
public record TransactionMessage(String bizNo, String type, String payload, TransactionMessageStatus status) {

    public TransactionMessage {
        Objects.requireNonNull(bizNo, "bizNo");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(status, "status");
        if (bizNo.isBlank()) {
            throw new IllegalArgumentException("bizNo 不能为空");
        }
    }

    public TransactionMessage withStatus(TransactionMessageStatus newStatus) {
        return new TransactionMessage(bizNo, type, payload, newStatus);
    }
}
