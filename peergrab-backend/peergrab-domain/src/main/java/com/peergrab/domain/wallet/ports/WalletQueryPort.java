package com.peergrab.domain.wallet.ports;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 钱包读模型端口。
 *
 * "我的任务""钱包流水"这类读需求不经过聚合根，直接走读模型——
 * CQRS 的最小形态：写走聚合（状态机、CAS），读走查询。
 */
public interface WalletQueryPort {

    /** 余额 */
    Optional<BalanceView> findBalance(long ownerId);

    /** 兼容旧客户端的页码分页；深页请使用 ledgerByCursor。 */
    List<LedgerView> ledger(long ownerId, int page, int size);

    /** 按流水归属用户、时间和 ID 游标分页，走 idx_ledger_user_time。 */
    List<LedgerView> ledgerByCursor(long ownerId, Instant beforeCreatedAt, Long beforeId, int size);

    record BalanceView(long availableCents, long frozenCents) {}

    /**
     * @param accountType 该笔流水所在账户的类型（USER/ESCROW/COMMISSION）；
     *                    托管/佣金账户的流水也归到当事人名下，前端需要区分展示。
     */
    record LedgerView(long id, Instant time, String direction, long amountCents,
                      String refType, long refId, String bizNo, String accountType) {}
}
