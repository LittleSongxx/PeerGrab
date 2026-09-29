package com.peergrab.application.usecase;

import com.peergrab.domain.credit.ports.CreditRankingPort;
import com.peergrab.domain.credit.ports.CreditRepository;
import com.peergrab.domain.credit.model.CreditEvent;
import com.peergrab.domain.errand.model.Errand;
import com.peergrab.domain.errand.model.ErrandStatus;
import com.peergrab.domain.errand.model.ErrandType;
import com.peergrab.domain.errand.ports.ErrandRepository;
import com.peergrab.domain.notify.ports.RealtimeNotifier;
import com.peergrab.domain.wallet.model.AccountType;
import com.peergrab.domain.wallet.model.EscrowOrder;
import com.peergrab.domain.wallet.model.WalletAccount;
import com.peergrab.domain.wallet.ports.FundAuditPort;
import com.peergrab.domain.wallet.ports.FundEventPort;
import com.peergrab.domain.wallet.ports.WalletRepository;
import com.peergrab.shared.BizException;
import com.peergrab.shared.ErrorCode;
import com.peergrab.shared.Money;
import com.peergrab.shared.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FundWorkflowUseCaseTest {

    private final ErrandRepository errands = mock(ErrandRepository.class);
    private final WalletRepository wallets = mock(WalletRepository.class);
    private final FundAuditPort audits = mock(FundAuditPort.class);
    private final FundEventPort events = mock(FundEventPort.class);
    private final CacheEvictSupport cache = mock(CacheEvictSupport.class);
    private final CreditRepository credits = mock(CreditRepository.class);
    private final CreditRankingPort ranking = mock(CreditRankingPort.class);
    private final RealtimeNotifier notifier = mock(RealtimeNotifier.class);
    private final CountingTxManager txManager = new CountingTxManager();
    private final TransactionTemplate tx = new TransactionTemplate(txManager);
    private final SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1);

    private SettleErrandUseCase settle;
    private RefundErrandUseCase refund;

    @BeforeEach
    void setUp() {
        settle = new SettleErrandUseCase(errands, wallets, audits, events, ids,
                cache, credits, ranking, tx, notifier, 0.05);
        refund = new RefundErrandUseCase(errands, wallets, audits, events, ids,
                cache, tx, notifier, credits, 9001);
    }

    @Test
    void publishedTaskCannotReleaseEscrow() {
        when(errands.findById(41)).thenReturn(Optional.of(errand(41, ErrandStatus.PUBLISHED)));

        BizException ex = assertThrows(BizException.class, () -> settle.settle(41, 1001));

        assertEquals(ErrorCode.ILLEGAL_STATE_TRANSITION, ex.code());
        verifyNoInteractions(wallets, events);
        assertEquals(0, txManager.commits + txManager.rollbacks);
    }

    @Test
    void runnerCannotSettleEvenWhenDelivered() {
        when(errands.findById(42)).thenReturn(Optional.of(errand(42, ErrandStatus.DELIVERED)));

        BizException ex = assertThrows(BizException.class, () -> settle.settle(42, 2001));

        assertEquals(ErrorCode.NOT_PUBLISHER, ex.code());
        verifyNoInteractions(wallets, events);
    }

    @Test
    void failedErrandCasDoesNotTouchEscrow() {
        when(errands.findById(43)).thenReturn(Optional.of(errand(43, ErrandStatus.DELIVERED)));
        when(wallets.findEscrowByErrandId(1, 43)).thenReturn(Optional.of(escrow(43)));
        when(errands.casSettle(43, 3)).thenReturn(0);

        assertEquals(SettleErrandUseCase.Result.CONFLICT, settle.settle(43, 1001));

        assertEquals(0, txManager.commits);
        assertEquals(1, txManager.rollbacks);
        verify(wallets, never()).casEscrowStatus(anyLong(), anyLong(), any(), any());
        verify(wallets, never()).casDebit(anyLong(), any());
        verifyNoInteractions(events);
    }

    @Test
    void failedEscrowCasRollsBackEarlierErrandCas() {
        when(errands.findById(50)).thenReturn(Optional.of(errand(50, ErrandStatus.DELIVERED)));
        when(wallets.findEscrowByErrandId(1, 50)).thenReturn(Optional.of(escrow(50)));
        when(errands.casSettle(50, 3)).thenReturn(1);
        when(wallets.casEscrowStatus(eq(1L), eq(50L), any(), any())).thenReturn(0);

        assertEquals(SettleErrandUseCase.Result.CONFLICT, settle.settle(50, 1001));

        assertEquals(0, txManager.commits);
        assertEquals(1, txManager.rollbacks);
        verify(errands, never()).appendStatusLog(anyLong(), any(), any(), anyInt(), anyLong());
        verifyNoInteractions(events);
    }

    @Test
    void failedEscrowDebitRollsBackSettlement() {
        when(errands.findById(44)).thenReturn(Optional.of(errand(44, ErrandStatus.DELIVERED)));
        when(wallets.findEscrowByErrandId(1, 44)).thenReturn(Optional.of(escrow(44)));
        when(wallets.casEscrowStatus(eq(1L), eq(44L), any(), any())).thenReturn(1);
        when(errands.casSettle(44, 3)).thenReturn(1);
        stubAccounts();
        when(wallets.casDebit(10, Money.ofCents(1000))).thenReturn(0);

        BizException ex = assertThrows(BizException.class, () -> settle.settle(44, 1001));

        assertEquals(ErrorCode.INSUFFICIENT_BALANCE, ex.code());
        assertEquals(1, txManager.rollbacks);
        verify(wallets, never()).insertLedger(any());
    }

    @Test
    void committedSettlementSharesOneCreditSnapshotBetweenRankingAndNotification() {
        when(errands.findById(55)).thenReturn(Optional.of(errand(55, ErrandStatus.DELIVERED)));
        when(wallets.findEscrowByErrandId(1, 55)).thenReturn(Optional.of(escrow(55)));
        when(errands.casSettle(55, 3)).thenReturn(1);
        when(wallets.casEscrowStatus(eq(1L), eq(55L), any(), any())).thenReturn(1);
        stubAccounts();
        when(wallets.casDebit(10, Money.ofCents(1000))).thenReturn(1);
        when(wallets.casCredit(30, Money.ofCents(950))).thenReturn(1);
        when(wallets.casCredit(40, Money.ofCents(50))).thenReturn(1);
        when(credits.scoreOf(2001)).thenReturn(62, 64);

        assertEquals(SettleErrandUseCase.Result.SETTLED, settle.settle(55, 1001));

        assertEquals(1, txManager.commits);
        verify(credits).applyEvent(any(CreditEvent.class));
        verify(credits, times(1)).scoreOf(2001);
        verify(ranking).update(1, 2001, 62);
        verify(notifier).creditChanged(2001, 62, 2, "完成结算");
    }

    @Test
    void autoSettlementBatchCommitsAllTransfersWithOneWalletBatch() {
        Errand first = errand(56, ErrandStatus.DELIVERED);
        Errand second = Errand.rehydrate(57, 1, 1001, ErrandType.DELIVERY,
                "task", Money.ofCents(1000), 1, 2002L, ErrandStatus.DELIVERED,
                1, 0, 3, null);
        when(wallets.findEscrowByErrandId(1, 56)).thenReturn(Optional.of(escrow(56)));
        when(wallets.findEscrowByErrandId(1, 57)).thenReturn(Optional.of(escrow(57)));
        when(errands.casAutoSettle(56, 3)).thenReturn(1);
        when(errands.casAutoSettle(57, 3)).thenReturn(1);
        when(wallets.casEscrowStatus(eq(1L), anyLong(), any(), any())).thenReturn(1);
        when(wallets.findByOwners(anyList())).thenReturn(Map.of(
                new WalletRepository.OwnerRef(-1, AccountType.ESCROW),
                account(10, -1, AccountType.ESCROW, 2_000),
                new WalletRepository.OwnerRef(-2, AccountType.COMMISSION),
                account(40, -2, AccountType.COMMISSION, 0),
                new WalletRepository.OwnerRef(2001, AccountType.USER),
                account(30, 2001, AccountType.USER, 0),
                new WalletRepository.OwnerRef(2002, AccountType.USER),
                account(31, 2002, AccountType.USER, 0)));
        when(wallets.lockAccountsInOrder(any(long[].class))).thenReturn(Map.of(
                10L, account(10, -1, AccountType.ESCROW, 2_000),
                30L, account(30, 2001, AccountType.USER, 0),
                31L, account(31, 2002, AccountType.USER, 0),
                40L, account(40, -2, AccountType.COMMISSION, 0)));

        SettleErrandUseCase.BatchResult result = settle.settleAutoBatch(List.of(first, second));

        assertEquals(new SettleErrandUseCase.BatchResult(2, 0), result);
        assertEquals(1, txManager.commits);
        assertEquals(0, txManager.rollbacks);
        verify(wallets).applyAccountUpdates(anyList());
        verify(wallets).insertLedgerBatch(anyList());
        verify(events, times(2)).append(any());
        verify(wallets, never()).casDebit(anyLong(), any());
        verify(wallets, never()).casCredit(anyLong(), any());
    }

    @Test
    void autoSettlementBatchRollsBackWholePageOnEscrowRace() {
        Errand first = errand(58, ErrandStatus.DELIVERED);
        Errand second = Errand.rehydrate(59, 1, 1001, ErrandType.DELIVERY,
                "task", Money.ofCents(1000), 1, 2002L, ErrandStatus.DELIVERED,
                1, 0, 3, null);
        when(wallets.findEscrowByErrandId(1, 58)).thenReturn(Optional.of(escrow(58)));
        when(wallets.findEscrowByErrandId(1, 59)).thenReturn(Optional.of(escrow(59)));
        when(errands.casAutoSettle(58, 3)).thenReturn(1);
        when(errands.casAutoSettle(59, 3)).thenReturn(1);
        when(wallets.casEscrowStatus(1, 58, EscrowOrder.EscrowStatus.HELD,
                EscrowOrder.EscrowStatus.RELEASED)).thenReturn(1);
        when(wallets.casEscrowStatus(1, 59, EscrowOrder.EscrowStatus.HELD,
                EscrowOrder.EscrowStatus.RELEASED)).thenReturn(0);

        assertThrows(RuntimeException.class, () -> settle.settleAutoBatch(List.of(first, second)));

        assertEquals(0, txManager.commits);
        assertEquals(1, txManager.rollbacks);
        verify(wallets, never()).applyAccountUpdates(anyList());
        verify(wallets, never()).insertLedgerBatch(anyList());
        verifyNoInteractions(events);
    }

    @Test
    void failedOutboxInsertRollsBackRefundTransaction() {
        when(errands.findById(45)).thenReturn(Optional.of(errand(45, ErrandStatus.PUBLISHED)));
        when(wallets.findEscrowByErrandId(1, 45)).thenReturn(Optional.of(escrow(45)));
        when(errands.casCancel(45, 3)).thenReturn(1);
        when(wallets.casEscrowStatus(eq(1L), eq(45L), any(), any())).thenReturn(1);
        stubAccounts();
        when(wallets.casDebit(10, Money.ofCents(1000))).thenReturn(1);
        when(wallets.casCredit(20, Money.ofCents(1000))).thenReturn(1);
        doThrow(new IllegalStateException("outbox insert failed"))
                .when(events).append(any());

        assertThrows(IllegalStateException.class, () -> refund.cancelAndRefund(45, 1001));

        verify(errands).casCancel(45, 3);
        verify(events).append(any());
        assertEquals(0, txManager.commits);
        assertEquals(1, txManager.rollbacks);
    }

    @Test
    void failedEscrowCasRollsBackCancellation() {
        when(errands.findById(46)).thenReturn(Optional.of(errand(46, ErrandStatus.PUBLISHED)));
        when(wallets.findEscrowByErrandId(1, 46)).thenReturn(Optional.of(escrow(46)));
        when(errands.casCancel(46, 3)).thenReturn(1);
        when(wallets.casEscrowStatus(eq(1L), eq(46L), any(), any())).thenReturn(0);

        assertEquals(RefundErrandUseCase.Result.CONFLICT, refund.cancelAndRefund(46, 1001));

        assertEquals(0, txManager.commits);
        assertEquals(1, txManager.rollbacks);
        verify(errands, never()).appendStatusLog(anyLong(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void successfulCancellationCommitsTaskAndFundsTogether() {
        when(errands.findById(47)).thenReturn(Optional.of(errand(47, ErrandStatus.PUBLISHED)));
        when(wallets.findEscrowByErrandId(1, 47)).thenReturn(Optional.of(escrow(47)));
        when(errands.casCancel(47, 3)).thenReturn(1);
        when(wallets.casEscrowStatus(eq(1L), eq(47L), any(), any())).thenReturn(1);
        stubAccounts();
        when(wallets.casDebit(10, Money.ofCents(1000))).thenReturn(1);
        when(wallets.casCredit(20, Money.ofCents(1000))).thenReturn(1);

        assertEquals(RefundErrandUseCase.Result.REFUNDED, refund.cancelAndRefund(47, 1001));

        assertEquals(1, txManager.commits);
        assertEquals(0, txManager.rollbacks);
        verify(errands).appendStatusLog(47, ErrandStatus.PUBLISHED, ErrandStatus.CANCELLED, 0, 1001);
        verify(wallets, times(2)).insertLedger(any());
        verify(events).append(any());
    }

    @Test
    void arbitrationRefundWritesCreditEventInsideFundsTransaction() {
        when(errands.findById(51)).thenReturn(Optional.of(errand(51, ErrandStatus.DISPUTED)));
        when(wallets.findEscrowByErrandId(1, 51)).thenReturn(Optional.of(escrow(51)));
        when(errands.casRefundFromDispute(51, 3)).thenReturn(1);
        when(wallets.casEscrowStatus(eq(1L), eq(51L), any(), any())).thenReturn(1);
        stubAccounts();
        when(wallets.casDebit(10, Money.ofCents(1000))).thenReturn(1);
        when(wallets.casCredit(20, Money.ofCents(1000))).thenReturn(1);

        assertEquals(RefundErrandUseCase.Result.REFUNDED, refund.arbitrateRefund(51, 9001));

        assertEquals(1, txManager.commits);
        verify(errands).appendStatusLog(51, ErrandStatus.DISPUTED, ErrandStatus.REFUNDED, 0, 9001);
        verify(credits).applyEvent(any(CreditEvent.class));
    }

    @Test
    void failedCreditEventRollsBackArbitrationRefund() {
        when(errands.findById(52)).thenReturn(Optional.of(errand(52, ErrandStatus.DISPUTED)));
        when(wallets.findEscrowByErrandId(1, 52)).thenReturn(Optional.of(escrow(52)));
        when(errands.casRefundFromDispute(52, 3)).thenReturn(1);
        when(wallets.casEscrowStatus(eq(1L), eq(52L), any(), any())).thenReturn(1);
        stubAccounts();
        when(wallets.casDebit(10, Money.ofCents(1000))).thenReturn(1);
        when(wallets.casCredit(20, Money.ofCents(1000))).thenReturn(1);
        when(credits.applyEvent(any())).thenThrow(new IllegalStateException("credit db unavailable"));

        assertThrows(IllegalStateException.class, () -> refund.arbitrateRefund(52, 9001));

        assertEquals(0, txManager.commits);
        assertEquals(1, txManager.rollbacks);
        verifyNoInteractions(events);
    }

    @Test
    void arbitrateRequiresConfiguredArbitratorBeforeReadingTask() {
        ArbitrateErrandUseCase arbitrate = new ArbitrateErrandUseCase(errands, wallets,
                refund, mock(ArbitrateSettleStep.class), audits, cache, credits,
                notifier, 9001);

        BizException ex = assertThrows(BizException.class,
                () -> arbitrate.arbitrate(48, ArbitrateErrandUseCase.Favor.RUNNER, 1001));

        assertEquals(ErrorCode.UNAUTHORIZED, ex.code());
        verifyNoInteractions(errands);
    }

    @Test
    void arbitrationEventReportsActualRunnerNetAndCommission() {
        var step = new ArbitrateSettleStep(errands, wallets, ids, credits, events, 0.05);
        when(errands.casSettleFromDispute(54, 3)).thenReturn(1);
        when(wallets.casEscrowStatus(eq(1L), eq(54L), any(), any())).thenReturn(1);
        stubAccounts();
        when(wallets.casDebit(10, Money.ofCents(1000))).thenReturn(1);
        when(wallets.casCredit(30, Money.ofCents(950))).thenReturn(1);
        when(wallets.casCredit(40, Money.ofCents(50))).thenReturn(1);

        assertTrue(step.settleFromDispute(54, errand(54, ErrandStatus.DISPUTED),
                escrow(54), "settle:54", 9001));

        var event = ArgumentCaptor.forClass(FundEventPort.FundEvent.class);
        verify(events).append(event.capture());
        assertEquals(950, event.getValue().amountCents());
        assertEquals(50, event.getValue().commissionCents());
    }

    @Test
    void refundBranchCannotBeInvokedDirectlyByNonArbitrator() {
        BizException ex = assertThrows(BizException.class,
                () -> refund.arbitrateRefund(49, 1001));

        assertEquals(ErrorCode.UNAUTHORIZED, ex.code());
        verifyNoInteractions(errands);
    }

    @Test
    void terminalTaskWithHeldEscrowIsNotReportedAsRefunded() {
        when(errands.findById(53)).thenReturn(Optional.of(errand(53, ErrandStatus.CANCELLED)));
        when(wallets.findEscrowByErrandId(1, 53)).thenReturn(Optional.of(escrow(53)));

        assertEquals(RefundErrandUseCase.Result.CONFLICT, refund.cancelAndRefund(53, 1001));

        verifyNoInteractions(events);
    }

    private void stubAccounts() {
        when(wallets.findByOwner(-1, AccountType.ESCROW))
                .thenReturn(Optional.of(account(10, -1, AccountType.ESCROW, 1000)));
        when(wallets.findByOwner(1001, AccountType.USER))
                .thenReturn(Optional.of(account(20, 1001, AccountType.USER, 0)));
        when(wallets.findByOwner(2001, AccountType.USER))
                .thenReturn(Optional.of(account(30, 2001, AccountType.USER, 0)));
        when(wallets.findByOwner(-2, AccountType.COMMISSION))
                .thenReturn(Optional.of(account(40, -2, AccountType.COMMISSION, 0)));
        when(wallets.lockAccountsInOrder(any(long[].class))).thenReturn(Map.of(
                10L, account(10, -1, AccountType.ESCROW, 1000),
                20L, account(20, 1001, AccountType.USER, 0),
                30L, account(30, 2001, AccountType.USER, 0),
                40L, account(40, -2, AccountType.COMMISSION, 0)));
    }

    private static WalletAccount account(long id, long ownerId, AccountType type, long available) {
        return new WalletAccount(id, ownerId, type, Money.ofCents(available), Money.ZERO, 0);
    }

    private static EscrowOrder escrow(long errandId) {
        return EscrowOrder.held(100 + errandId, 1, errandId, 1001, Money.ofCents(1000));
    }

    private static Errand errand(long id, ErrandStatus status) {
        Long runner = status == ErrandStatus.PUBLISHED ? null : 2001L;
        return Errand.rehydrate(id, 1, 1001, ErrandType.DELIVERY,
                "task", Money.ofCents(1000), 1, runner, status, runner == null ? 0 : 1,
                0, 3, null);
    }

    private static final class CountingTxManager implements PlatformTransactionManager {
        int commits;
        int rollbacks;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            if (status.isRollbackOnly()) rollbacks++;
            else commits++;
        }

        @Override
        public void rollback(TransactionStatus status) {
            rollbacks++;
        }
    }
}
