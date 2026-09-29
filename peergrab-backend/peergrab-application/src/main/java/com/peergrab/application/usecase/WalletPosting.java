package com.peergrab.application.usecase;

import com.peergrab.domain.wallet.model.LedgerEntry;
import com.peergrab.domain.wallet.model.WalletAccount;
import com.peergrab.domain.wallet.ports.WalletRepository;
import com.peergrab.shared.BizException;
import com.peergrab.shared.ErrorCode;
import com.peergrab.shared.Money;
import com.peergrab.shared.SnowflakeIdGenerator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A balanced transfer. The caller's transaction covers every account update and ledger row. */
final class WalletPosting {

    record Leg(long accountId, long ledgerUserId, LedgerEntry.Direction direction, Money amount) {
        static Leg debit(long accountId, long ledgerUserId, Money amount) {
            return new Leg(accountId, ledgerUserId, LedgerEntry.Direction.DEBIT, amount);
        }

        static Leg credit(long accountId, long ledgerUserId, Money amount) {
            return new Leg(accountId, ledgerUserId, LedgerEntry.Direction.CREDIT, amount);
        }
    }

    /** One balanced transfer in a multi-errand settlement transaction. */
    record BatchTransfer(String bizNo, LedgerEntry.RefType refType, long refId, List<Leg> legs) {}

    private static final class BalanceState {
        private final long accountId;
        private final long expectedAvailable;
        private final long expectedVersion;
        private long available;
        private long version;

        private BalanceState(WalletAccount account) {
            this.accountId = account.id();
            this.expectedAvailable = account.available().cents();
            this.expectedVersion = account.version();
            this.available = expectedAvailable;
            this.version = expectedVersion;
        }
    }

    private WalletPosting() {}

    static void post(WalletRepository wallets, SnowflakeIdGenerator ids, String bizNo,
                     LedgerEntry.RefType refType, long refId, List<Leg> legs) {
        if (legs.size() < 2) throw new IllegalArgumentException("transfer needs at least two legs");
        long debits = 0;
        long credits = 0;
        Set<Long> accountIds = new HashSet<>();
        for (Leg leg : legs) {
            if (leg.amount() == null || leg.amount().cents() <= 0 || !accountIds.add(leg.accountId())) {
                throw new IllegalArgumentException("each transfer leg needs a positive amount and distinct account");
            }
            if (leg.direction() == LedgerEntry.Direction.DEBIT) {
                debits = Math.addExact(debits, leg.amount().cents());
            } else {
                credits = Math.addExact(credits, leg.amount().cents());
            }
        }
        if (debits != credits) throw new IllegalArgumentException("debits and credits must balance");

        // SELECT FOR UPDATE uses a current read even under MySQL REPEATABLE READ. Lock every
        // participating account in the same ID order before mutating any account. The returned
        // balance/version are therefore current and exclusive until the caller commits.
        Map<Long, WalletAccount> locked = wallets.lockAccountsInOrder(
                legs.stream().mapToLong(Leg::accountId).toArray());
        for (Leg leg : legs) {
            WalletAccount account = locked.get(leg.accountId());
            if (account == null) {
                throw new BizException(ErrorCode.ACCOUNT_NOT_FOUND, "account=" + leg.accountId());
            }
            boolean debit = leg.direction() == LedgerEntry.Direction.DEBIT;
            long afterCents;
            if (debit) {
                if (account.available().compareTo(leg.amount()) < 0
                        || wallets.casDebit(leg.accountId(), leg.amount()) != 1) {
                    throw new BizException(ErrorCode.INSUFFICIENT_BALANCE,
                            "account=" + leg.accountId() + " required=" + leg.amount());
                }
                afterCents = Math.subtractExact(account.available().cents(), leg.amount().cents());
            } else {
                afterCents = Math.addExact(account.available().cents(), leg.amount().cents());
                if (wallets.casCredit(leg.accountId(), leg.amount()) != 1) {
                    throw new BizException(ErrorCode.ACCOUNT_NOT_FOUND, "account=" + leg.accountId());
                }
            }
            wallets.insertLedger(new LedgerEntry(ids.nextId(), bizNo, leg.accountId(),
                    leg.ledgerUserId(), leg.direction(), leg.amount(), Money.ofCents(afterCents),
                    Math.addExact(account.version(), 1), refType, refId));
        }
    }

    /**
     * Posts several balanced transfers while holding the participating wallet rows
     * only once.  The caller owns one surrounding database transaction.  This is
     * intentionally separate from {@link #post}: the single-transfer path keeps
     * its established call sequence, while the automatic-settlement worker can
     * aggregate a bounded page without changing its accounting rules.
     */
    static void postBatch(WalletRepository wallets, SnowflakeIdGenerator ids,
                          List<BatchTransfer> transfers) {
        if (transfers == null || transfers.isEmpty()) return;

        List<Long> accountIds = new ArrayList<>();
        for (BatchTransfer transfer : transfers) {
            if (transfer == null) throw new IllegalArgumentException("batch transfer is null");
            validateTransfer(transfer.bizNo(), transfer.legs());
            for (Leg leg : transfer.legs()) accountIds.add(leg.accountId());
        }

        Map<Long, WalletAccount> locked = wallets.lockAccountsInOrder(
                accountIds.stream().mapToLong(Long::longValue).toArray());
        Map<Long, BalanceState> states = new LinkedHashMap<>();
        List<LedgerEntry> entries = new ArrayList<>();

        for (BatchTransfer transfer : transfers) {
            for (Leg leg : transfer.legs()) {
                WalletAccount account = locked.get(leg.accountId());
                if (account == null) {
                    throw new BizException(ErrorCode.ACCOUNT_NOT_FOUND,
                            "account=" + leg.accountId());
                }
                BalanceState state = states.computeIfAbsent(account.id(), ignored -> new BalanceState(account));
                long amount = leg.amount().cents();
                if (leg.direction() == LedgerEntry.Direction.DEBIT) {
                    if (state.available < amount) {
                        throw new BizException(ErrorCode.INSUFFICIENT_BALANCE,
                                "account=" + leg.accountId() + " required=" + leg.amount());
                    }
                    state.available = Math.subtractExact(state.available, amount);
                } else {
                    state.available = Math.addExact(state.available, amount);
                }
                state.version = Math.addExact(state.version, 1);
                entries.add(new LedgerEntry(ids.nextId(), transfer.bizNo(), leg.accountId(),
                        leg.ledgerUserId(), leg.direction(), leg.amount(), Money.ofCents(state.available),
                        state.version, transfer.refType(), transfer.refId()));
            }
        }

        List<WalletRepository.AccountUpdate> updates = states.values().stream()
                .sorted(java.util.Comparator.comparingLong(state -> state.accountId))
                .map(state -> new WalletRepository.AccountUpdate(state.accountId,
                        state.expectedAvailable, state.available, state.expectedVersion, state.version))
                .toList();
        wallets.applyAccountUpdates(updates);
        wallets.insertLedgerBatch(entries);
    }

    private static void validateTransfer(String bizNo, List<Leg> legs) {
        if (bizNo == null || bizNo.isBlank() || legs == null || legs.size() < 2) {
            throw new IllegalArgumentException("batch transfer needs a bizNo and at least two legs");
        }
        long debits = 0;
        long credits = 0;
        Set<Long> accountIds = new HashSet<>();
        for (Leg leg : legs) {
            if (leg == null || leg.amount() == null || leg.amount().cents() <= 0
                    || !accountIds.add(leg.accountId())) {
                throw new IllegalArgumentException("each transfer leg needs a positive amount and distinct account");
            }
            if (leg.direction() == LedgerEntry.Direction.DEBIT) {
                debits = Math.addExact(debits, leg.amount().cents());
            } else {
                credits = Math.addExact(credits, leg.amount().cents());
            }
        }
        if (debits != credits) throw new IllegalArgumentException("debits and credits must balance");
    }
}
