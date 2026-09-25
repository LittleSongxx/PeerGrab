package com.peergrab.application.usecase;

import com.peergrab.domain.credit.model.CreditEventType;
import com.peergrab.domain.credit.ports.CreditRepository;
import com.peergrab.domain.notify.ports.RealtimeNotifier;
import com.peergrab.domain.errand.model.Errand;
import com.peergrab.domain.errand.model.ErrandStatus;
import com.peergrab.domain.errand.ports.ErrandRepository;
import com.peergrab.domain.wallet.model.EscrowOrder;
import com.peergrab.domain.wallet.model.LedgerEntry;
import com.peergrab.domain.wallet.ports.FundAuditPort;
import com.peergrab.domain.wallet.ports.FundEventPort;
import com.peergrab.domain.wallet.ports.WalletRepository;
import com.peergrab.shared.BizException;
import com.peergrab.shared.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 仲裁用例：DISPUTED -> SETTLED（支持跑腿）或 DISPUTED -> REFUNDED（支持发单人）。
 *
 * 只做全额分账两种结果，不做按比例部分分账——比例分账会牵出
 * "佣金怎么算""余数归谁""退多少算合理"一堆边界，而教学价值增量很小。
 *
 * 支持发单人的分支直接复用 RefundErrandUseCase.arbitrateRefund；
 * 支持跑腿的分支需要单独实现，因为它的入口状态是 DISPUTED 而不是 DELIVERED，
 * SettleErrandUseCase 的 casSettle 只认 DELIVERED。
 */
@Service
public class ArbitrateErrandUseCase {

    private final ErrandRepository errandRepository;
    private final WalletRepository walletRepository;
    private final RefundErrandUseCase refundUseCase;
    private final ArbitrateSettleStep settleStep;
    private final FundAuditPort auditPort;
    private final FundEventPort fundEventPort;
    private final CacheEvictSupport cacheEvict;
    private final CreditRepository creditRepository;
    private final RealtimeNotifier notifier;
    private final long arbitratorId;

    public ArbitrateErrandUseCase(ErrandRepository errandRepository,
                                  WalletRepository walletRepository,
                                  RefundErrandUseCase refundUseCase,
                                  ArbitrateSettleStep settleStep,
                                  FundAuditPort auditPort,
                                  FundEventPort fundEventPort,
                                  CacheEvictSupport cacheEvict,
                                  CreditRepository creditRepository,
                                  RealtimeNotifier notifier,
                                  @Value("${peergrab.auth.arbitrator-id:9001}") long arbitratorId) {
        this.errandRepository = errandRepository;
        this.walletRepository = walletRepository;
        this.refundUseCase = refundUseCase;
        this.settleStep = settleStep;
        this.auditPort = auditPort;
        this.fundEventPort = fundEventPort;
        this.cacheEvict = cacheEvict;
        this.creditRepository = creditRepository;
        this.notifier = notifier;
        this.arbitratorId = arbitratorId;
    }

    /** 仲裁裁决方向 */
    public enum Favor { RUNNER, PUBLISHER }

    public enum Result { SETTLED_TO_RUNNER, REFUNDED_TO_PUBLISHER, ALREADY_DONE, CONFLICT }

    public Result arbitrate(long errandId, Favor favor, long operatorId) {
        if (operatorId != arbitratorId) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "仲裁员身份不匹配 actor=" + operatorId);
        }
        Errand errand = errandRepository.findById(errandId)
                .orElseThrow(() -> new BizException(ErrorCode.ERRAND_NOT_FOUND, "id=" + errandId));
        if (errand.status() != ErrandStatus.DISPUTED) {
            // 已经裁决过了（SETTLED/REFUNDED 都是终态）
            if (errand.status() == ErrandStatus.SETTLED || errand.status() == ErrandStatus.REFUNDED) {
                return Result.ALREADY_DONE;
            }
            throw new BizException(ErrorCode.NOT_ARBITRABLE, "status=" + errand.status());
        }

        if (favor == Favor.PUBLISHER) {
            var r = refundUseCase.arbitrateRefund(errandId, operatorId);
            if (r == RefundErrandUseCase.Result.REFUNDED) {
                if (errand.grabberId() != null) {
                    notifier.creditChanged(errand.grabberId(),
                            creditRepository.scoreOf(errand.grabberId()),
                            CreditEventType.DISPUTE_LOSE.delta(), "争议败诉");
                }
            }
            return r == RefundErrandUseCase.Result.REFUNDED
                    ? Result.REFUNDED_TO_PUBLISHER
                    : (r == RefundErrandUseCase.Result.ALREADY_REFUNDED ? Result.ALREADY_DONE : Result.CONFLICT);
        }

        // 支持跑腿：走与结算相同的资金分配，但入口状态是 DISPUTED
        EscrowOrder escrow = walletRepository.findEscrowByErrandId(errand.campusId(), errandId)
                .orElseThrow(() -> new BizException(ErrorCode.ESCROW_NOT_FOUND, "errandId=" + errandId));
        if (escrow.status() != EscrowOrder.EscrowStatus.HELD) {
            return Result.ALREADY_DONE;
        }

        String bizNo = LedgerEntry.settleBizNo(errandId);
        var event = new FundEventPort.FundEvent(bizNo, "ARBITRATED", errandId, errand.publisherId(),
                errand.grabberId() == null ? 0L : errand.grabberId(), escrow.amount().cents(), 0);

        boolean committed = WalletDeadlockRetry.execute(() -> fundEventPort.publishInTransaction(event,
                () -> settleStep.settleFromDispute(errandId, errand, escrow, bizNo, operatorId)));

        if (committed) {
            cacheEvict.evictAfterCommit(errandId);
            auditPort.record(bizNo, "ARBITRATE", errandId, operatorId,
                    String.format("{\"favor\":\"RUNNER\",\"amount\":%d}", escrow.amount().cents()),
                    true, null);
            notifier.errandStatusChanged(errandId, errand.publisherId(), errand.grabberId(),
                    "SETTLED", errand.round());
            return Result.SETTLED_TO_RUNNER;
        }
        return Result.CONFLICT;
    }
}
