package com.peergrab.application.usecase;

import com.peergrab.domain.credit.model.CreditEvent;
import com.peergrab.domain.credit.model.CreditEventType;
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
import com.peergrab.shared.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 退款用例。两种触发场景：
 *   1. 发单人取消（DRAFT/PUBLISHED → CANCELLED）
 *   2. 仲裁支持发单人（DISPUTED → REFUNDED）
 *
 * 资金流向：托管账户 → 发单人账户，全额退回，无佣金。
 */
@Service
public class RefundErrandUseCase {

    private static final long ESCROW_ACCOUNT_OWNER = -1L;

    private final ErrandRepository errandRepository;
    private final WalletRepository walletRepository;
    private final FundAuditPort auditPort;
    private final FundEventPort fundEventPort;
    private final SnowflakeIdGenerator idGenerator;
    private final CacheEvictSupport cacheEvict;
    private final TransactionTemplate transactionTemplate;
    private final RealtimeNotifier notifier;
    private final CreditRepository creditRepository;
    private final long arbitratorId;

    public RefundErrandUseCase(ErrandRepository errandRepository,
                               WalletRepository walletRepository,
                               FundAuditPort auditPort,
                               FundEventPort fundEventPort,
                               SnowflakeIdGenerator idGenerator,
                               CacheEvictSupport cacheEvict,
                               TransactionTemplate transactionTemplate,
                               RealtimeNotifier notifier,
                               CreditRepository creditRepository,
                               @Value("${peergrab.auth.arbitrator-id:9001}") long arbitratorId) {
        this.errandRepository = errandRepository;
        this.walletRepository = walletRepository;
        this.auditPort = auditPort;
        this.fundEventPort = fundEventPort;
        this.idGenerator = idGenerator;
        this.cacheEvict = cacheEvict;
        this.transactionTemplate = transactionTemplate;
        this.notifier = notifier;
        this.creditRepository = creditRepository;
        this.arbitratorId = arbitratorId;
    }

    public enum Result { REFUNDED, ALREADY_REFUNDED, CONFLICT }

    /** 发单人主动取消 */
    public Result cancelAndRefund(long errandId, long publisherId) {
        Errand errand = errandRepository.findById(errandId)
                .orElseThrow(() -> new BizException(ErrorCode.ERRAND_NOT_FOUND, "id=" + errandId));
        if (errand.publisherId() != publisherId) {
            throw new BizException(ErrorCode.NOT_PUBLISHER, "actor=" + publisherId);
        }
        if (errand.status() == ErrandStatus.CANCELLED) return existingRefundResult(errand);
        ErrandStatus from = errand.status();
        long version = errand.version();
        errand.cancelByPublisher(publisherId, version);
        return doRefund(errandId, errand, from, ErrandStatus.CANCELLED, version, publisherId);
    }

    /** 仲裁支持发单人 */
    public Result arbitrateRefund(long errandId, long operatorId) {
        if (operatorId != arbitratorId) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "仲裁员身份不匹配 actor=" + operatorId);
        }
        Errand errand = errandRepository.findById(errandId)
                .orElseThrow(() -> new BizException(ErrorCode.ERRAND_NOT_FOUND, "id=" + errandId));
        if (errand.status() == ErrandStatus.REFUNDED) return existingRefundResult(errand);
        if (errand.status() != ErrandStatus.DISPUTED) {
            throw new BizException(ErrorCode.NOT_ARBITRABLE, "status=" + errand.status());
        }
        long version = errand.version();
        errand.arbitrateToPublisher(version);
        return doRefund(errandId, errand, ErrandStatus.DISPUTED,
                ErrandStatus.REFUNDED, version, operatorId);
    }

    private Result existingRefundResult(Errand errand) {
        EscrowOrder escrow = walletRepository.findEscrowByErrandId(errand.campusId(), errand.id())
                .orElseThrow(() -> new BizException(ErrorCode.ESCROW_NOT_FOUND,
                        "errandId=" + errand.id()));
        return escrow.status() == EscrowOrder.EscrowStatus.REFUNDED
                ? Result.ALREADY_REFUNDED : Result.CONFLICT;
    }

    private Result doRefund(long errandId, Errand errand, ErrandStatus from,
                            ErrandStatus to, long expectedVersion, long operatorId) {
        EscrowOrder escrow = walletRepository.findEscrowByErrandId(errand.campusId(), errandId)
                .orElseThrow(() -> new BizException(ErrorCode.ESCROW_NOT_FOUND, "errandId=" + errandId));
        if (escrow.status() != EscrowOrder.EscrowStatus.HELD) {
            return Result.CONFLICT;
        }

        String bizNo = LedgerEntry.refundBizNo(errandId);
        FundEvent event = new FundEvent(bizNo, "REFUNDED", errandId, errand.publisherId(),
                errand.grabberId() != null ? errand.grabberId() : 0L, escrow.amount().cents(), 0);

        // 与结算同理：lambda 自调用下 @Transactional 不生效（P3 遗留隐患，P5 修复），
        // 改用程序化事务保证退款动作的原子性
        boolean committed = WalletDeadlockRetry.execute(() -> fundEventPort.publishInTransaction(event, () ->
                Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                    boolean applied = doRefundInTx(errand, escrow, bizNo, from, to,
                            expectedVersion, operatorId);
                    if (!applied) status.setRollbackOnly();
                    return applied;
                }))));
        if (committed) {
            cacheEvict.evictAfterCommit(errandId);
            notifier.errandStatusChanged(errandId, errand.publisherId(), errand.grabberId(),
                    to.name(), errand.round());
            auditPort.record(bizNo, "REFUND", errandId, operatorId,
                    String.format("{\"amount\":%d}", escrow.amount().cents()), true, null);
            return Result.REFUNDED;
        }
        return Result.CONFLICT;
    }

    /** 退款事务体，由 TransactionTemplate 包裹（见 doRefund 注释） */
    boolean doRefundInTx(Errand errand, EscrowOrder escrow, String bizNo,
                         ErrandStatus from, ErrandStatus to, long expectedVersion, long operatorId) {
        long errandId = errand.id();
        int updated = to == ErrandStatus.CANCELLED
                ? errandRepository.casCancel(errandId, expectedVersion)
                : errandRepository.casRefundFromDispute(errandId, expectedVersion);
        if (updated == 0) return false;

        int escrowed = walletRepository.casEscrowStatus(errand.campusId(), errandId,
                EscrowOrder.EscrowStatus.HELD, EscrowOrder.EscrowStatus.REFUNDED);
        if (escrowed == 0) return false;

        errandRepository.appendStatusLog(errandId, from, to, errand.round(), operatorId);

        WalletAccount escrowAccount = walletRepository.findByOwner(ESCROW_ACCOUNT_OWNER, AccountType.ESCROW).orElseThrow();
        WalletAccount publisherAccount = walletRepository.findByOwner(escrow.publisherId(), AccountType.USER).orElseThrow();

        WalletPosting.post(walletRepository, idGenerator, bizNo, LedgerEntry.RefType.REFUND,
                errandId, List.of(
                        WalletPosting.Leg.debit(escrowAccount.id(), escrow.publisherId(), escrow.amount()),
                        WalletPosting.Leg.credit(publisherAccount.id(), escrow.publisherId(), escrow.amount())));
        if (to == ErrandStatus.REFUNDED && errand.grabberId() != null) {
            creditRepository.applyEvent(new CreditEvent(
                    idGenerator.nextId(),
                    CreditEvent.disputeLoseBizNo(errandId, errand.grabberId()),
                    errand.grabberId(), CreditEventType.DISPUTE_LOSE,
                    CreditEventType.DISPUTE_LOSE.delta(), "ERRAND", errandId,
                    java.time.Instant.now()));
        }
        return true;
    }
}
