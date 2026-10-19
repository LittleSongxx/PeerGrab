package com.peergrab.lab.txmsg;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 纯内存的 RocketMQ 5.x 事务消息 Broker 仿真：
 * 半消息 -> 本地事务 -> commit/rollback -> 三态回查。
 *
 * <p>与真实实现的映射：
 * <ul>
 *   <li>{@link #beginTransaction(TransactionMessage)} 对应
 *       {@code Producer.beginTransaction()} 发送半消息：消息已进 Broker 但消费者不可见；
 *       发送失败抛 {@link HalfMessageSendException}，调用方不得执行本地事务
 *       （5.x gRPC 客户端此时不会返回 {@code Transaction}，应用也就无从 commit）。</li>
 *   <li>{@link #commit(String)} / {@link #rollback(String)} 对应
 *       {@code Transaction#commit()} / {@code Transaction#rollback()}；提交只投递一次，
 *       重复 commit 幂等返回 {@code false}。</li>
 *   <li>{@link #checkBack(String, TransactionChecker)} 对应 Broker 事务回查：
 *       本地事务结果未知时调用 {@link TransactionChecker#check(TransactionMessage)}，
 *       按 {@link TransactionResolution} 三态决定提交、回滚或继续等待。</li>
 *   <li>真实 Broker 的 {@code transactionCheckInterval} 默认 60s、最多回查 15 次。
 *       lab 用可注入 {@link Clock} 模拟“60s 宽限”：宽限内 UNKNOWN 保留半消息，
 *       超宽限仍无可靠事实则 {@link TransactionMessageStatus#DISCARDED}。</li>
 * </ul>
 *
 * <p>5.x gRPC 客户端把本地事务交给应用显式 commit/rollback；
 * {@link #sendMessageInTransaction(TransactionMessage, Supplier)} 只是把
 * “先发半消息、成功后才执行本地事务”的顺序固化成可测试的辅助方法，便于演示失败短路。
 *
 * <p>本类只存在于离线模块，不进入 API/worker 运行包，也不引入任何 MQ 客户端依赖；
 * 为保持演示简单，它不是线程安全的。
 */
public final class InMemoryTransactionBroker {

    /** 真实 Broker 默认事务回查间隔 60s；lab 把它当作一次性的回查宽限。 */
    public static final Duration CHECK_BACK_GRACE = Duration.ofSeconds(60);

    /** 半消息发送通道；返回 false 表示 Broker 未确认写入。 */
    @FunctionalInterface
    public interface HalfMessageSender {
        boolean send(TransactionMessage halfMessage);
    }

    /** 半消息发送失败；抛出它意味着本地事务绝不能开始。 */
    public static final class HalfMessageSendException extends RuntimeException {
        public HalfMessageSendException(String bizNo, String reason) {
            super("半消息发送失败，本地事务不执行: bizNo=" + bizNo + ", reason=" + reason);
        }
    }

    private record Slot(TransactionMessage message, Instant preparedAt) {
        Slot withStatus(TransactionMessageStatus status) {
            return new Slot(message.withStatus(status), preparedAt);
        }
    }

    private final Clock clock;
    private final HalfMessageSender halfMessageSender;
    private final Map<String, Slot> slots = new LinkedHashMap<>();
    private final Deque<TransactionMessage> pendingDeliveries = new ArrayDeque<>();
    private final List<TransactionMessage> deliveryLog = new ArrayList<>();

    public InMemoryTransactionBroker() {
        this(Clock.systemUTC(), ignored -> true);
    }

    public InMemoryTransactionBroker(Clock clock) {
        this(clock, ignored -> true);
    }

    public InMemoryTransactionBroker(Clock clock, HalfMessageSender halfMessageSender) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.halfMessageSender = Objects.requireNonNull(halfMessageSender, "halfMessageSender");
    }

    /**
     * 发送半消息：写入成功才返回 PREPARED 快照，失败抛异常且不留下任何记录。
     */
    public TransactionMessage beginTransaction(TransactionMessage draft) {
        Objects.requireNonNull(draft, "draft");
        if (slots.containsKey(draft.bizNo())) {
            throw new IllegalStateException("重复的半消息 bizNo: " + draft.bizNo());
        }
        TransactionMessage halfMessage = draft.withStatus(TransactionMessageStatus.PREPARED);
        if (!halfMessageSender.send(halfMessage)) {
            throw new HalfMessageSendException(halfMessage.bizNo(), "half message send returned false");
        }
        slots.put(halfMessage.bizNo(), new Slot(halfMessage, clock.instant()));
        return halfMessage;
    }

    /**
     * 复刻 {@code sendMessageInTransaction} 的顺序保证：半消息失败时本地事务回调不会执行。
     *
     * <p>本地事务返回 {@link TransactionResolution}：COMMIT/ROLLBACK 立即推进，
     * UNKNOWN 保留半消息等待回查。返回推进后的最新快照。
     */
    public TransactionMessage sendMessageInTransaction(TransactionMessage draft,
                                                       Supplier<TransactionResolution> localTransaction) {
        Objects.requireNonNull(localTransaction, "localTransaction");
        TransactionMessage halfMessage = beginTransaction(draft);
        TransactionResolution resolution = Objects.requireNonNull(
                localTransaction.get(), "localTransaction 返回值不能为 null");
        switch (resolution) {
            case COMMIT -> commit(halfMessage.bizNo());
            case ROLLBACK -> rollback(halfMessage.bizNo());
            case UNKNOWN -> {
                // 本地事务尚未出结果，保持 PREPARED，交给回查。
            }
        }
        return requireSlot(halfMessage.bizNo()).message();
    }

    /**
     * 提交半消息：状态 PREPARED -> COMMITTED，并加入消费者投递队列。
     *
     * @return 本次是否真的发生了状态迁移；重复 commit、回滚后 commit 均返回 false
     */
    public boolean commit(String bizNo) {
        Slot slot = requireSlot(bizNo);
        if (slot.message().status() != TransactionMessageStatus.PREPARED) {
            return false;
        }
        Slot committed = slot.withStatus(TransactionMessageStatus.COMMITTED);
        slots.put(bizNo, committed);
        pendingDeliveries.add(committed.message());
        deliveryLog.add(committed.message());
        return true;
    }

    /**
     * 回滚半消息：状态 PREPARED -> ROLLED_BACK，消息永久不可见。
     *
     * @return 本次是否真的发生了状态迁移
     */
    public boolean rollback(String bizNo) {
        Slot slot = requireSlot(bizNo);
        if (slot.message().status() != TransactionMessageStatus.PREPARED) {
            return false;
        }
        slots.put(bizNo, slot.withStatus(TransactionMessageStatus.ROLLED_BACK));
        return true;
    }

    /**
     * Broker 回查入口，使用 Broker 的时钟。
     */
    public TransactionResolution checkBack(String bizNo, TransactionChecker checker) {
        return checkBack(bizNo, checker, clock.instant());
    }

    /**
     * Broker 回查入口。
     *
     * <p>已 COMMITTED 的消息直接返回 COMMIT；已 ROLLED_BACK/DISCARDED 直接返回 ROLLBACK，
     * 都不会再次询问 checker（真实 Broker 也不会回查已结束的事务）。
     * PREPARED 时调用 checker：
     * COMMIT/ROLLBACK 立即推进；UNKNOWN 在 60s 宽限内保留，
     * 超过宽限仍无法确认则丢弃并返回 ROLLBACK。
     *
     * @param now 注入的当前时间，用于在测试中压缩 60s 宽限
     */
    public TransactionResolution checkBack(String bizNo, TransactionChecker checker, Instant now) {
        Objects.requireNonNull(checker, "checker");
        Objects.requireNonNull(now, "now");
        Slot slot = requireSlot(bizNo);
        switch (slot.message().status()) {
            case COMMITTED -> {
                return TransactionResolution.COMMIT;
            }
            case ROLLED_BACK, DISCARDED -> {
                return TransactionResolution.ROLLBACK;
            }
            case PREPARED -> {
                // 继续走回查。
            }
        }

        TransactionResolution resolution = Objects.requireNonNull(
                checker.check(slot.message()), "checker 返回值不能为 null");
        switch (resolution) {
            case COMMIT -> {
                commit(bizNo);
                return TransactionResolution.COMMIT;
            }
            case ROLLBACK -> {
                rollback(bizNo);
                return TransactionResolution.ROLLBACK;
            }
            case UNKNOWN -> {
                if (Duration.between(slot.preparedAt(), now).compareTo(CHECK_BACK_GRACE) >= 0) {
                    discard(bizNo);
                    return TransactionResolution.ROLLBACK;
                }
                return TransactionResolution.UNKNOWN;
            }
        }
        throw new IllegalStateException("未知的回查结论: " + resolution);
    }

    /** 当前状态；bizNo 不存在时抛 {@link NoSuchElementException}。 */
    public TransactionMessageStatus statusOf(String bizNo) {
        return requireSlot(bizNo).message().status();
    }

    /** 半消息是否成功落盘（用于断言发送失败没有副作用）。 */
    public boolean contains(String bizNo) {
        return slots.containsKey(bizNo);
    }

    /** 已提交消息的投递日志快照；每条消息最多出现一次。 */
    public List<TransactionMessage> visibleMessages() {
        return List.copyOf(deliveryLog);
    }

    /**
     * 模拟消费者拉取：返回并移除队首已提交消息，保证同一条消息只投递一次。
     */
    public Optional<TransactionMessage> pollVisible() {
        return Optional.ofNullable(pendingDeliveries.poll());
    }

    private boolean discard(String bizNo) {
        Slot slot = requireSlot(bizNo);
        if (slot.message().status() != TransactionMessageStatus.PREPARED) {
            return false;
        }
        slots.put(bizNo, slot.withStatus(TransactionMessageStatus.DISCARDED));
        return true;
    }

    private Slot requireSlot(String bizNo) {
        Slot slot = slots.get(bizNo);
        if (slot == null) {
            throw new NoSuchElementException("不存在的 bizNo: " + bizNo);
        }
        return slot;
    }
}
