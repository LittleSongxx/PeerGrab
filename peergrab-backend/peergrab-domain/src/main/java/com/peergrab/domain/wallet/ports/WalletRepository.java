package com.peergrab.domain.wallet.ports;

import com.peergrab.domain.wallet.model.AccountType;
import com.peergrab.domain.wallet.model.EscrowOrder;
import com.peergrab.domain.wallet.model.LedgerEntry;
import com.peergrab.domain.wallet.model.WalletAccount;
import com.peergrab.shared.Money;

import java.util.Optional;
import java.util.Map;

public interface WalletRepository {

    Optional<WalletAccount> findByOwner(long ownerId, AccountType type);

    Optional<WalletAccount> findById(long accountId);

    /**
     * 在当前数据库事务中按账户 ID 升序锁定全部参与账户，返回锁内最新余额和版本。
     * 后续余额 CAS 与流水写入必须在同一事务内完成；统一顺序避免系统户与用户户反向等待。
     */
    Map<Long, WalletAccount> lockAccountsInOrder(long... accountIds);

    /**
     * 带条件的 CAS 转出：available -= amount，条件是 available >= amount。
     * 普通 SELECT 后不能直接按旧值 UPDATE；资金过账先 FOR UPDATE 锁定账户，
     * 这里仍保留数据库条件校验作为余额下限的最后防线。
     *
     * P3 修正：原名 casFreeze 且同时写 frozen，那是"预授权"语义。
     * 本项目的托管是资金真实转移，钱必须离开付款方账户，否则同一笔钱被记两次。
     *
     * @return 影响行数，0 表示余额不足或并发冲突
     */
    int casDebit(long accountId, Money amount);

    /** 贷记到目标账户（托管账户收款 / 跑腿收款 / 佣金入账） */
    int casCredit(long accountId, Money amount);

    /** 写流水。bizNo + direction + account 唯一索引冲突时抛异常，代表重复请求 */
    void insertLedger(LedgerEntry entry);

    void insertEscrow(EscrowOrder order);

    boolean escrowExists(long campusId, long errandId);

    Optional<EscrowOrder> findEscrowByErrandId(long campusId, long errandId);

    /**
     * 托管单状态 CAS：HELD -> RELEASED/REFUNDED。
     * 条件里带 expectedStatus 是并发结算的第一道闸门——
     * 两个线程同时结算，只有一个能把 HELD 改掉，另一个 affectedRows=0。
     */
    int casEscrowStatus(long campusId, long errandId, EscrowOrder.EscrowStatus from, EscrowOrder.EscrowStatus to);

    /** 幂等判定：该 bizNo 是否已有流水。用于事务消息回查与重复请求识别 */
    boolean ledgerExists(String bizNo);
}
