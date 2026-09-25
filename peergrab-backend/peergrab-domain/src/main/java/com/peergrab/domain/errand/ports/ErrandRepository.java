package com.peergrab.domain.errand.ports;

import com.peergrab.domain.errand.model.Errand;
import com.peergrab.domain.errand.model.ErrandStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 任务仓储端口。定义在领域层，由 infrastructure 提供适配器实现（依赖倒置）。
 * 领域层不知道底层是 MySQL 还是别的东西。
 */
public interface ErrandRepository {

    void insert(Errand errand);

    Optional<Errand> findById(long errandId);

    /**
     * 抢单的核心 CAS：只有当前状态与版本号都匹配时才更新成功。
     *
     * @return 影响行数。0 表示状态已被别人改掉（抢单失败），1 表示抢中。
     *         用 affectedRows 判定而不是先查后改，是为了让判断和更新成为数据库层的一个原子动作。
     */
    int casLockForRunner(long errandId, long runnerId, long expectedVersion);

    /** 发布：DRAFT -> PUBLISHED，同样用 CAS 保证不会重复发布 */
    int casPublish(long errandId, long expectedVersion);

    void appendStatusLog(long errandId, ErrandStatus from, ErrandStatus to, int round, long operatorId);

    /** 跑腿确认接单：LOCKED -> ACCEPTED，只有当前抢中者本人能确认 */
    int casAccept(long errandId, long runnerId, long expectedVersion);

    /**
     * 超时流转给下一位：LOCKED -> LOCKED，round+1。
     * 条件里带 round 是幂等的关键——旧轮次的消息 CAS 必然失败。
     */
    int casTransferToNext(long errandId, long nextRunnerId, long expectedVersion, int expectedRound);

    /** 候选队列空，回退重新开放：LOCKED -> PUBLISHED，名额还回去 */
    int casRevertToPublished(long errandId, long expectedVersion, int expectedRound);

    /** 兜底扫描：捞出确认超时仍停留在 LOCKED 的任务（走 idx_timeout_scan） */
    List<Errand> findConfirmTimeout(long timeoutSeconds, int limit);

    /** 带时间和 id 游标，避免最早的一批持续失败时饿死后续任务。 */
    List<Errand> findConfirmTimeoutAfter(long timeoutSeconds, Instant afterAt, long afterId, int limit);

    /** 跑腿取货：ACCEPTED -> PICKED_UP */
    int casPickUp(long errandId, long runnerId, long expectedVersion);

    /** 跑腿送达：PICKED_UP -> DELIVERED，同时写 delivered_at（自动结算扫描要用） */
    int casDeliver(long errandId, long runnerId, long expectedVersion);

    /**
     * 结算：DELIVERED -> SETTLED。
     * 这是并发重复结算的第一道闸门；随后校验 escrow 状态 CAS 与 ledger 唯一索引。
     */
    int casSettle(long errandId, long expectedVersion);

    /** 仲裁退款：DISPUTED -> REFUNDED */
    int casRefundFromDispute(long errandId, long expectedVersion);

    /** 发单人取消：DRAFT/PUBLISHED -> CANCELLED，名额清零 */
    int casCancel(long errandId, long expectedVersion);

    /** 发起争议：ACCEPTED/PICKED_UP/DELIVERED -> DISPUTED */
    int casDispute(long errandId, long expectedVersion);

    /** 仲裁支持跑腿：DISPUTED -> SETTLED */
    int casSettleFromDispute(long errandId, long expectedVersion);

    /** 兜底扫描：捞出送达后超过窗口仍未确认的任务，用于自动结算 */
    List<Errand> findAutoSettleDue(long autoSettleSeconds, int limit);

    /** 带时间和 id 游标，避免最早的一批持续失败时饿死后续任务。 */
    List<Errand> findAutoSettleDueAfter(long autoSettleSeconds, Instant afterAt, long afterId, int limit);
}
