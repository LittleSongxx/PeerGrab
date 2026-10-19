package com.peergrab.lab.txmsg;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * 只读账本事实的三态回查器：账本流水存在则 COMMIT，否则 UNKNOWN。
 *
 * <p>账本事实由 {@link Predicate} 注入（生产中通常是
 * {@code wallet_ledger.biz_no} 是否存在），因此本类不依赖任务状态、仓储实现或任何可变业务对象。
 *
 * <p><b>为什么事实缺失返回 UNKNOWN 而不是 ROLLBACK：</b>
 * “查不到流水”可能只是本地事务尚未提交、主从延迟或连接抖动，并不等于本地事务失败。
 * 贸然 ROLLBACK 会让 Broker 永久丢弃一条本该提交的消息；因此缺席一律交给 Broker 的
 * 60s 宽限与重试机制兜底，只有确定失败才应由实现返回 ROLLBACK。
 *
 * <p>对应真实 RocketMQ 5.x 的 {@code TransactionChecker#check}；
 * PeerGrab 生产资金链路仍使用 outbox，本类仅用于 lab。
 */
public final class LedgerFactChecker implements TransactionChecker {

    private final Predicate<String> ledgerFactExists;

    /**
     * @param ledgerFactExists 入参为 {@link TransactionMessage#bizNo()}，返回账本中是否已有对应流水
     */
    public LedgerFactChecker(Predicate<String> ledgerFactExists) {
        this.ledgerFactExists = Objects.requireNonNull(ledgerFactExists, "ledgerFactExists");
    }

    @Override
    public TransactionResolution check(TransactionMessage halfMessage) {
        Objects.requireNonNull(halfMessage, "halfMessage");
        return ledgerFactExists.test(halfMessage.bizNo())
                ? TransactionResolution.COMMIT
                : TransactionResolution.UNKNOWN;
    }
}
