package com.peergrab.application.usecase;

import com.peergrab.domain.wallet.model.AccountType;
import com.peergrab.domain.wallet.model.LedgerEntry;
import com.peergrab.domain.wallet.model.WalletAccount;
import com.peergrab.domain.wallet.ports.WalletRepository;
import com.peergrab.shared.BizException;
import com.peergrab.shared.ErrorCode;
import com.peergrab.shared.Money;
import com.peergrab.shared.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WalletPostingTest {

    private final WalletRepository wallets = mock(WalletRepository.class);
    private final SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1);

    @Test
    void ledgerUsesCurrentLockedBalancesAndAccountVersions() {
        // Earlier non-locking reads may have seen different values. The locked rows are authoritative.
        when(wallets.lockAccountsInOrder(any(long[].class))).thenReturn(Map.of(
                1L, account(1, -1, AccountType.ESCROW, 5_000, 8),
                1001L, account(1001, 1001, AccountType.USER, 12_000, 3)));
        when(wallets.casDebit(1001, Money.ofCents(900))).thenReturn(1);
        when(wallets.casCredit(1, Money.ofCents(900))).thenReturn(1);

        WalletPosting.post(wallets, ids, "escrow:77", LedgerEntry.RefType.ESCROW, 77,
                List.of(WalletPosting.Leg.debit(1001, 1001, Money.ofCents(900)),
                        WalletPosting.Leg.credit(1, 1001, Money.ofCents(900))));

        ArgumentCaptor<LedgerEntry> entries = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(wallets, times(2)).insertLedger(entries.capture());
        LedgerEntry debit = entries.getAllValues().get(0);
        LedgerEntry credit = entries.getAllValues().get(1);
        assertEquals(11_100, debit.balanceAfter().cents());
        assertEquals(4, debit.accountVersion());
        assertEquals(5_900, credit.balanceAfter().cents());
        assertEquals(9, credit.accountVersion());
        InOrder order = inOrder(wallets);
        order.verify(wallets).lockAccountsInOrder(1001, 1);
        order.verify(wallets).casDebit(1001, Money.ofCents(900));
        order.verify(wallets).insertLedger(debit);
        order.verify(wallets).casCredit(1, Money.ofCents(900));
        order.verify(wallets).insertLedger(credit);
    }

    @Test
    void rejectsUnbalancedTransferBeforeLockingAnyAccount() {
        assertThrows(IllegalArgumentException.class, () -> WalletPosting.post(wallets, ids,
                "settle:77", LedgerEntry.RefType.SETTLE, 77,
                List.of(WalletPosting.Leg.debit(1, -1, Money.ofCents(900)),
                        WalletPosting.Leg.credit(1001, 1001, Money.ofCents(899)))));
        verifyNoInteractions(wallets);
    }

    @Test
    void insufficientLockedBalanceDoesNotWriteLedger() {
        when(wallets.lockAccountsInOrder(any(long[].class))).thenReturn(Map.of(
                1L, account(1, -1, AccountType.ESCROW, 100, 8),
                1001L, account(1001, 1001, AccountType.USER, 500, 3)));

        BizException error = assertThrows(BizException.class, () -> WalletPosting.post(wallets,
                ids, "refund:77", LedgerEntry.RefType.REFUND, 77,
                List.of(WalletPosting.Leg.debit(1, -1, Money.ofCents(900)),
                        WalletPosting.Leg.credit(1001, 1001, Money.ofCents(900)))));

        assertEquals(ErrorCode.INSUFFICIENT_BALANCE, error.code());
        verify(wallets, never()).insertLedger(any());
        verify(wallets, never()).casDebit(anyLong(), any());
    }

    private static WalletAccount account(long id, long ownerId, AccountType type,
                                         long available, long version) {
        return new WalletAccount(id, ownerId, type, Money.ofCents(available), Money.ZERO, version);
    }
}
