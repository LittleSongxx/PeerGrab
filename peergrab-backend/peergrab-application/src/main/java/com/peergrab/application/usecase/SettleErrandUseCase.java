package com.peergrab.application.usecase;

import com.peergrab.domain.credit.model.CreditEvent;
import com.peergrab.domain.credit.model.CreditEventType;
import com.peergrab.domain.credit.ports.CreditRankingPort;
import com.peergrab.domain.credit.ports.CreditRepository;
import com.peergrab.domain.notify.ports.RealtimeNotifier;
import com.peergrab.domain.errand.model.Errand;
import com.peergrab.domain.errand.model.ErrandStatus;
import com.peergrab.domain.errand.ports.ErrandRepository;
import com.peergrab.domain.wallet.model.*;
import com.peergrab.domain.wallet.ports.FundAuditPort;
import com.peergrab.domain.wallet.ports.FundEventPort;
import com.peergrab.domain.wallet.ports.FundEventPort.FundEvent;
import com.peergrab.domain.wallet.ports.WalletRepository;
import com.peergrab.shared.BizException;
import com.peergrab.shared.ErrorCode;
import com.peergrab.shared.Money;
import com.peergrab.shared.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 结算用例：DELIVERED -> SETTLED，资金从托管账户分配给跑腿与佣金账户。
 *
 * ── 并发幂等三道闸门 ──
 *   1. errand CAS：DELIVERED -> SETTLED，只有一个线程能改成功
 *   2. escrow_order 状态 CAS：HELD -> RELEASED，只有一个线程能改成功
 *   3. wallet_ledger 唯一索引：biz_no='settle:{id}' 重复插入撞索引
 * 三道独立的闸门确保"即使前面的被绕过（理论上不可能），后面的也会兜住"。
 *
 * ── 佣金计算 ──
 * 佣金 = 托管金额 × commissionRate，向下取整到分。
 * 跑腿所得 = 托管金额 - 佣金。
 * 这样"跑腿 + 佣金 == 托管"恒成立，不会因四舍五入丢 1 分。
 */
@Service
public class SettleErrandUseCase {

    private static final Logger log = LoggerFactory.getLogger(SettleErrandUseCase.class);
    private static final long ESCROW_ACCOUNT_OWNER = -1L;
    private static final long COMMISSION_ACCOUNT_OWNER = -2L;

    private final ErrandRepository errandRepository;
    private final WalletRepository walletRepository;
    private final FundAuditPort auditPort;
    private final FundEventPort fundEventPort;
    private final SnowflakeIdGenerator idGenerator;
    private final double commissionRate;
    private final CacheEvictSupport cacheEvict;
    private final CreditRepository creditRepository;
    private final CreditRankingPort creditRankingPort;
    private final TransactionTemplate transactionTemplate;
    private final RealtimeNotifier notifier;

    public SettleErrandUseCase(ErrandRepository errandRepository,
                               WalletRepository walletRepository,
                               FundAuditPort auditPort,
                               FundEventPort fundEventPort,
                               SnowflakeIdGenerator idGenerator,
                               CacheEvictSupport cacheEvict,
                               CreditRepository creditRepository,
                               CreditRankingPort creditRankingPort,
                               TransactionTemplate transactionTemplate,
                               RealtimeNotifier notifier,
                               @Value("${peergrab.settle.commission-rate:0.05}") double commissionRate) {
        this.errandRepository = errandRepository;
        this.walletRepository = walletRepository;
        this.auditPort = auditPort;
        this.fundEventPort = fundEventPort;
        this.idGenerator = idGenerator;
        this.cacheEvict = cacheEvict;
        this.creditRepository = creditRepository;
        this.creditRankingPort = creditRankingPort;
        this.transactionTemplate = transactionTemplate;
        this.notifier = notifier;
        this.commissionRate = commissionRate;
    }

    public enum Result { SETTLED, ALREADY_SETTLED, CONFLICT }

    /** Result of one bounded automatic-settlement batch. */
    public record BatchResult(int settled, int skipped) {}

    private record AutoSettlement(Errand errand, EscrowOrder escrow,
                                  Money runnerAmount, Money commissionAmount,
                                  String bizNo, FundEvent event) {}

    /** Signals a concurrent manual settlement; the enclosing batch must roll back. */
    private static final class BatchConflictException extends RuntimeException {
        private BatchConflictException(long errandId) {
            super("automatic settlement batch conflicted on errandId=" + errandId);
        }
    }

    /**
     * @param operatorId 发单人确认时传发单人 ID；自动结算时传 Errand.SYSTEM_OPERATOR
     */
    public Result settle(long errandId, long operatorId) {
        Errand errand = errandRepository.findById(errandId)
                .orElseThrow(() -> new BizException(ErrorCode.ERRAND_NOT_FOUND, "id=" + errandId));

        if (operatorId != Errand.SYSTEM_OPERATOR && operatorId != errand.publisherId()) {
            throw new BizException(ErrorCode.NOT_PUBLISHER, "actor=" + operatorId);
        }

        if (errand.status() == ErrandStatus.SETTLED) {
            return Result.ALREADY_SETTLED;
        }
        if (errand.status() != ErrandStatus.DELIVERED) {
            throw new BizException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "只有已送达任务可以结算，status=" + errand.status());
        }

        EscrowOrder escrow = walletRepository.findEscrowByErrandId(errand.campusId(), errandId)
                .orElseThrow(() -> new BizException(ErrorCode.ESCROW_NOT_FOUND, "errandId=" + errandId));

        // 计算佣金（向下取整，余数归跑腿）
        long totalCents = escrow.amount().cents();
        long commissionCents = (long) Math.floor(totalCents * commissionRate);
        long runnerCents = totalCents - commissionCents;
        Money runnerAmount = Money.ofCents(runnerCents);
        Money commissionAmount = Money.ofCents(commissionCents);

        String bizNo = LedgerEntry.settleBizNo(errandId);
        FundEvent event = new FundEvent(bizNo, "SETTLED", errandId, errand.publisherId(),
                errand.grabberId() != null ? errand.grabberId() : 0L, runnerCents, commissionCents);

        // 一个用例事务涵盖状态、资金、信用和 outbox；CAS 冲突回滚整个事务。
        boolean committed = WalletDeadlockRetry.execute(() ->
                Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                    boolean applied = doSettle(errandId, errand, escrow,
                            runnerAmount, commissionAmount, bizNo, operatorId);
                    if (!applied) {
                        status.setRollbackOnly();
                        return false;
                    }
                    fundEventPort.append(event);
                    return true;
                })));

        if (committed) {
            // 状态变为 SETTLED，详情缓存失效。这里事务已提交（TransactionTemplate 返回后），
            // 所以 evictAfterCommit 会走"无事务上下文"分支立即删除
            cacheEvict.evictAfterCommit(errandId);
            // 排行榜是展示层：事务提交后更新，Redis 失败由每日校准 job 自愈
            Integer runnerScore = errand.grabberId() == null
                    ? null : creditRepository.scoreOf(errand.grabberId());
            if (errand.grabberId() != null) {
                creditRankingPort.update(errand.campusId(), errand.grabberId(),
                        runnerScore);
            }
            auditPort.record(bizNo, "SETTLE", errandId, operatorId,
                    String.format("{\"runner\":%d,\"commission\":%d}", runnerCents, commissionCents),
                    true, null);
            // 实时推送：状态变更 + 跑腿信用分变化
            notifier.errandStatusChanged(errandId, errand.publisherId(), errand.grabberId(),
                    com.peergrab.domain.errand.model.ErrandStatus.SETTLED.name(), errand.round());
            if (errand.grabberId() != null) {
                notifier.creditChanged(errand.grabberId(),
                        runnerScore,
                        com.peergrab.domain.credit.model.CreditEventType.SETTLE.delta(),
                        "完成结算");
            }
            return Result.SETTLED;
        }
        return Result.CONFLICT;
    }

    /**
     * Settles a bounded page of due errands in one transaction.  Every errand
     * still passes the same errand/escrow CAS gates as the single-item path.  A
     * concurrent escrow race aborts the whole page so the worker can replay the
     * page through the original idempotent single-item method; this prevents a
     * partially applied batch from ever being reported as successful.
     */
    public BatchResult settleAutoBatch(List<Errand> dueErrands) {
        if (dueErrands == null || dueErrands.isEmpty()) return new BatchResult(0, 0);

        List<AutoSettlement> candidates = new ArrayList<>(dueErrands.size());
        int skipped = 0;
        for (Errand errand : dueErrands) {
            if (errand == null || errand.status() != ErrandStatus.DELIVERED) {
                skipped++;
                continue;
            }
            if (errand.grabberId() == null) {
                throw new IllegalStateException("delivered errand has no grabberId errandId=" + errand.id());
            }
            EscrowOrder escrow = walletRepository.findEscrowByErrandId(errand.campusId(), errand.id())
                    .orElseThrow(() -> new BizException(ErrorCode.ESCROW_NOT_FOUND,
                            "errandId=" + errand.id()));
            long totalCents = escrow.amount().cents();
            long commissionCents = (long) Math.floor(totalCents * commissionRate);
            long runnerCents = totalCents - commissionCents;
            String bizNo = LedgerEntry.settleBizNo(errand.id());
            candidates.add(new AutoSettlement(errand, escrow, Money.ofCents(runnerCents),
                    Money.ofCents(commissionCents), bizNo,
                    new FundEvent(bizNo, "SETTLED", errand.id(), errand.publisherId(),
                            errand.grabberId(), runnerCents, commissionCents)));
        }
        if (candidates.isEmpty()) return new BatchResult(0, skipped);

        List<AutoSettlement> committed = new ArrayList<>(candidates.size());
        final int initialSkipped = skipped;
        BatchResult transactionResult = WalletDeadlockRetry.execute(() ->
                transactionTemplate.execute(status -> {
                    committed.clear();
                    List<AutoSettlement> applied = new ArrayList<>(candidates.size());
                    int transactionSkipped = initialSkipped;
                    for (AutoSettlement item : candidates) {
                        Errand errand = item.errand();
                        if (errandRepository.casAutoSettle(errand.id(), errand.version()) == 0) {
                            transactionSkipped++;
                            continue;
                        }
                        if (walletRepository.casEscrowStatus(errand.campusId(), errand.id(),
                                EscrowOrder.EscrowStatus.HELD, EscrowOrder.EscrowStatus.RELEASED) == 0) {
                            throw new BatchConflictException(errand.id());
                        }
                        errandRepository.appendStatusLog(errand.id(), ErrandStatus.DELIVERED,
                                ErrandStatus.SETTLED, errand.round(), Errand.SYSTEM_OPERATOR);
                        applied.add(item);
                    }
                    if (applied.isEmpty()) return new BatchResult(0, transactionSkipped);

                    List<WalletRepository.OwnerRef> refs = new ArrayList<>(applied.size() + 2);
                    refs.add(new WalletRepository.OwnerRef(ESCROW_ACCOUNT_OWNER, AccountType.ESCROW));
                    refs.add(new WalletRepository.OwnerRef(COMMISSION_ACCOUNT_OWNER, AccountType.COMMISSION));
                    for (AutoSettlement item : applied) {
                        refs.add(new WalletRepository.OwnerRef(item.errand().grabberId(), AccountType.USER));
                    }
                    Map<WalletRepository.OwnerRef, WalletAccount> accounts = walletRepository.findByOwners(refs);
                    List<WalletPosting.BatchTransfer> transfers = new ArrayList<>(applied.size());
                    for (AutoSettlement item : applied) {
                        Errand errand = item.errand();
                        WalletAccount escrowAccount = accountOrFallback(accounts,
                                new WalletRepository.OwnerRef(ESCROW_ACCOUNT_OWNER, AccountType.ESCROW));
                        WalletAccount runnerAccount = accountOrFallback(accounts,
                                new WalletRepository.OwnerRef(errand.grabberId(), AccountType.USER));
                        WalletAccount commissionAccount = accountOrFallback(accounts,
                                new WalletRepository.OwnerRef(COMMISSION_ACCOUNT_OWNER, AccountType.COMMISSION));
                        List<WalletPosting.Leg> legs = new ArrayList<>();
                        legs.add(WalletPosting.Leg.debit(escrowAccount.id(), errand.publisherId(), item.escrow().amount()));
                        legs.add(WalletPosting.Leg.credit(runnerAccount.id(), errand.grabberId(), item.runnerAmount()));
                        if (item.commissionAmount().cents() > 0) {
                            legs.add(WalletPosting.Leg.credit(commissionAccount.id(),
                                    COMMISSION_ACCOUNT_OWNER, item.commissionAmount()));
                        }
                        transfers.add(new WalletPosting.BatchTransfer(item.bizNo(), LedgerEntry.RefType.SETTLE,
                                errand.id(), legs));
                    }
                    WalletPosting.postBatch(walletRepository, idGenerator, transfers);
                    for (AutoSettlement item : applied) {
                        Errand errand = item.errand();
                        creditRepository.applyEvent(new CreditEvent(
                                idGenerator.nextId(), CreditEvent.settleBizNo(errand.id()), errand.grabberId(),
                                CreditEventType.SETTLE, CreditEventType.SETTLE.delta(), "ERRAND", errand.id(),
                                java.time.Instant.now()));
                        fundEventPort.append(item.event());
                    }
                    committed.addAll(applied);
                    return new BatchResult(applied.size(), transactionSkipped);
                }));

        // Preserve the single-item post-commit side effects.  They are deliberately
        // outside the long account-locking transaction and may be retried by the
        // normal cache/ranking/audit reconciliation paths if an external system is down.
        for (AutoSettlement item : committed) {
            Errand errand = item.errand();
            cacheEvict.evictAfterCommit(errand.id());
            Integer runnerScore = creditRepository.scoreOf(errand.grabberId());
            creditRankingPort.update(errand.campusId(), errand.grabberId(), runnerScore);
            auditPort.record(item.bizNo(), "SETTLE", errand.id(), Errand.SYSTEM_OPERATOR,
                    String.format("{\"runner\":%d,\"commission\":%d}",
                            item.runnerAmount().cents(), item.commissionAmount().cents()), true, null);
            notifier.errandStatusChanged(errand.id(), errand.publisherId(), errand.grabberId(),
                    ErrandStatus.SETTLED.name(), errand.round());
            notifier.creditChanged(errand.grabberId(), runnerScore,
                    CreditEventType.SETTLE.delta(), "完成结算");
        }
        return transactionResult;
    }

    /**
     * 结算的事务体。由 TransactionTemplate 包裹执行（见 settle 里的注释），
     * 不依赖 @Transactional 代理——lambda 自调用场景下代理不可靠。
     */
    boolean doSettle(long errandId, Errand errand, EscrowOrder escrow,
                     Money runnerAmount, Money commissionAmount, String bizNo, long operatorId) {
        // 所有资金终态先锁任务、再锁托管单，避免结算与仲裁退款互相等待。
        int errandUpdated = operatorId == Errand.SYSTEM_OPERATOR
                ? errandRepository.casAutoSettle(errandId, errand.version())
                : errandRepository.casSettle(errandId, errand.version());
        if (errandUpdated == 0) {
            log.info("结算幂等：errand 已非 DELIVERED errandId={}", errandId);
            return false;
        }
        int escrowUpdated = walletRepository.casEscrowStatus(errand.campusId(), errandId,
                EscrowOrder.EscrowStatus.HELD, EscrowOrder.EscrowStatus.RELEASED);
        if (escrowUpdated == 0) {
            log.info("结算冲突：escrow 已非 HELD errandId={}", errandId);
            return false;
        }
        errandRepository.appendStatusLog(errandId,
                com.peergrab.domain.errand.model.ErrandStatus.DELIVERED,
                com.peergrab.domain.errand.model.ErrandStatus.SETTLED,
                errand.round(), operatorId);

        // 资金转移：托管账户 -> 跑腿 + 佣金
        var escrowRef = new WalletRepository.OwnerRef(ESCROW_ACCOUNT_OWNER, AccountType.ESCROW);
        var runnerRef = new WalletRepository.OwnerRef(errand.grabberId(), AccountType.USER);
        var commissionRef = new WalletRepository.OwnerRef(COMMISSION_ACCOUNT_OWNER, AccountType.COMMISSION);
        Map<WalletRepository.OwnerRef, WalletAccount> accounts = walletRepository.findByOwners(
                List.of(escrowRef, runnerRef, commissionRef));
        // Keep the legacy lookups as a compatibility fallback for alternate adapters and
        // older test doubles; JdbcWalletRepository uses one indexed query for all three.
        WalletAccount escrowAccount = accountOrFallback(accounts, escrowRef);
        WalletAccount runnerAccount = accountOrFallback(accounts, runnerRef);
        WalletAccount commissionAccount = accountOrFallback(accounts, commissionRef);

        var legs = new ArrayList<WalletPosting.Leg>();
        legs.add(WalletPosting.Leg.debit(escrowAccount.id(), errand.publisherId(), escrow.amount()));
        legs.add(WalletPosting.Leg.credit(runnerAccount.id(), errand.grabberId(), runnerAmount));
        if (commissionAmount.cents() > 0) {
            legs.add(WalletPosting.Leg.credit(commissionAccount.id(),
                    COMMISSION_ACCOUNT_OWNER, commissionAmount));
        }
        WalletPosting.post(walletRepository, idGenerator, bizNo, LedgerEntry.RefType.SETTLE,
                errandId, legs);

        // 信用事件与资金动作同事务：结算成功则跑腿 +2 分，无中间态（P5 验收标准第 5 条）。
        // biz_no 唯一索引保证重复结算不重复计分
        creditRepository.applyEvent(new CreditEvent(
                idGenerator.nextId(), CreditEvent.settleBizNo(errandId), errand.grabberId(),
                CreditEventType.SETTLE, CreditEventType.SETTLE.delta(), "ERRAND", errandId,
                java.time.Instant.now()));
        return true;
    }

    private WalletAccount accountOrFallback(Map<WalletRepository.OwnerRef, WalletAccount> accounts,
                                            WalletRepository.OwnerRef ref) {
        WalletAccount account = accounts == null ? null : accounts.get(ref);
        if (account != null) return account;
        return walletRepository.findByOwner(ref.ownerId(), ref.type()).orElseThrow();
    }
}
