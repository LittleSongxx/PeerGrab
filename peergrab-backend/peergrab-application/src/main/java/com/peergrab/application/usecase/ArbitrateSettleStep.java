package com.peergrab.application.usecase;

import com.peergrab.domain.credit.model.CreditEvent;
import com.peergrab.domain.credit.model.CreditEventType;
import com.peergrab.domain.credit.ports.CreditRepository;
import com.peergrab.domain.errand.model.Errand;
import com.peergrab.domain.errand.model.ErrandStatus;
import com.peergrab.domain.errand.ports.ErrandRepository;
import com.peergrab.domain.wallet.model.*;
import com.peergrab.domain.wallet.ports.WalletRepository;
import com.peergrab.shared.Money;
import com.peergrab.shared.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.util.ArrayList;

/**
 * 仲裁裁给跑腿时的事务性资金步骤。
 *
 * 单独拆成一个 Bean 而不是写在 ArbitrateErrandUseCase 里，是为了避开
 * @Transactional 同类自调用失效——同一个坑 P1 用 GrabTransactionalStep 避过、
 * P2 在 TimeoutTransferUseCase 上重犯过一次，这里不再重复。
 */
@Component
public class ArbitrateSettleStep {

    private static final Logger log = LoggerFactory.getLogger(ArbitrateSettleStep.class);
    private static final long ESCROW_ACCOUNT_OWNER = -1L;
    private static final long COMMISSION_ACCOUNT_OWNER = -2L;

    private final ErrandRepository errandRepository;
    private final WalletRepository walletRepository;
    private final SnowflakeIdGenerator idGenerator;
    private final CreditRepository creditRepository;
    private final double commissionRate;

    public ArbitrateSettleStep(ErrandRepository errandRepository,
                              WalletRepository walletRepository,
                              SnowflakeIdGenerator idGenerator,
                              CreditRepository creditRepository,
                              @Value("${peergrab.settle.commission-rate:0.05}") double commissionRate) {
        this.errandRepository = errandRepository;
        this.walletRepository = walletRepository;
        this.idGenerator = idGenerator;
        this.creditRepository = creditRepository;
        this.commissionRate = commissionRate;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean settleFromDispute(long errandId, Errand errand, EscrowOrder escrow,
                                     String bizNo, long operatorId) {
        // 与普通结算、退款使用相同锁顺序：先任务，再托管单。
        if (errandRepository.casSettleFromDispute(errandId, errand.version()) == 0) {
            log.info("仲裁结算幂等：errand 已非 DISPUTED errandId={}", errandId);
            return false;
        }
        if (walletRepository.casEscrowStatus(errand.campusId(), errandId,
                EscrowOrder.EscrowStatus.HELD, EscrowOrder.EscrowStatus.RELEASED) == 0) {
            log.info("仲裁结算冲突：escrow 已非 HELD errandId={}", errandId);
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return false;
        }
        errandRepository.appendStatusLog(errandId, ErrandStatus.DISPUTED, ErrandStatus.SETTLED,
                errand.round(), operatorId);

        long total = escrow.amount().cents();
        long commissionCents = (long) Math.floor(total * commissionRate);
        Money runnerAmount = Money.ofCents(total - commissionCents);
        Money commissionAmount = Money.ofCents(commissionCents);

        WalletAccount escrowAccount = walletRepository
                .findByOwner(ESCROW_ACCOUNT_OWNER, AccountType.ESCROW).orElseThrow();
        WalletAccount runnerAccount = walletRepository
                .findByOwner(errand.grabberId(), AccountType.USER).orElseThrow();
        WalletAccount commissionAccount = walletRepository
                .findByOwner(COMMISSION_ACCOUNT_OWNER, AccountType.COMMISSION).orElseThrow();

        var legs = new ArrayList<WalletPosting.Leg>();
        legs.add(WalletPosting.Leg.debit(escrowAccount.id(), errand.publisherId(), escrow.amount()));
        legs.add(WalletPosting.Leg.credit(runnerAccount.id(), errand.grabberId(), runnerAmount));
        if (commissionCents > 0) {
            legs.add(WalletPosting.Leg.credit(commissionAccount.id(),
                    COMMISSION_ACCOUNT_OWNER, commissionAmount));
        }
        WalletPosting.post(walletRepository, idGenerator, bizNo, LedgerEntry.RefType.SETTLE,
                errandId, legs);
        // 仲裁支持跑腿：跑腿无过错，按正常结算计分（同事务）
        creditRepository.applyEvent(new CreditEvent(
                idGenerator.nextId(), CreditEvent.settleBizNo(errandId), errand.grabberId(),
                CreditEventType.SETTLE, CreditEventType.SETTLE.delta(), "ERRAND", errandId,
                java.time.Instant.now()));
        return true;
    }
}
