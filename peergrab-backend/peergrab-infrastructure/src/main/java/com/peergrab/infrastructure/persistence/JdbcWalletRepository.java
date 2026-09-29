package com.peergrab.infrastructure.persistence;

import com.peergrab.domain.wallet.model.AccountType;
import com.peergrab.domain.wallet.model.EscrowOrder;
import com.peergrab.domain.wallet.model.LedgerEntry;
import com.peergrab.domain.wallet.model.WalletAccount;
import com.peergrab.domain.wallet.ports.WalletRepository;
import com.peergrab.shared.Money;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.sql.Statement;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Repository
public class JdbcWalletRepository implements WalletRepository {

    private final JdbcTemplate jdbc;
    private final Timer accountLockTimer;
    private final Timer ownerLookupTimer;

    public JdbcWalletRepository(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.accountLockTimer = Timer.builder("peergrab.wallet.account.lock")
                .description("Time to fetch and lock all participating wallet rows, including SQL and lock wait")
                .serviceLevelObjectives(Duration.ofMillis(1), Duration.ofMillis(5),
                        Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50),
                        Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500),
                        Duration.ofSeconds(1))
                .register(registry);
        this.ownerLookupTimer = Timer.builder("peergrab.wallet.owner.lookup")
                .description("Time to batch-resolve settlement wallet owners")
                .publishPercentileHistogram()
                .register(registry);
    }

    private static final RowMapper<WalletAccount> MAPPER = (rs, n) -> new WalletAccount(
            rs.getLong("id"),
            rs.getLong("owner_id"),
            AccountType.valueOf(rs.getString("owner_type")),
            Money.ofCents(rs.getLong("available")),
            Money.ofCents(rs.getLong("frozen")),
            rs.getLong("version"));

    @Override
    public Map<WalletRepository.OwnerRef, WalletAccount> findByOwners(List<WalletRepository.OwnerRef> owners) {
        if (owners == null || owners.isEmpty()) return Map.of();
        long started = System.nanoTime();
        try {
            // Each OR arm is an equality probe on uk_owner(owner_id, owner_type);
            // settlement needs the IDs only so the subsequent lock query can use PKs.
            List<WalletRepository.OwnerRef> distinct = owners.stream().distinct().toList();
            String predicates = String.join(" OR ",
                    java.util.Collections.nCopies(distinct.size(), "(owner_id = ? AND owner_type = ?)"));
            Object[] args = new Object[distinct.size() * 2];
            for (int i = 0; i < distinct.size(); i++) {
                args[i * 2] = distinct.get(i).ownerId();
                args[i * 2 + 1] = distinct.get(i).type().name();
            }
            Map<WalletRepository.OwnerRef, WalletAccount> result = new LinkedHashMap<>();
            for (WalletAccount account : jdbc.query("SELECT * FROM wallet_account WHERE " + predicates
                            + " ORDER BY id", MAPPER, args)) {
                result.put(new WalletRepository.OwnerRef(account.ownerId(), account.type()), account);
            }
            return result;
        } finally {
            ownerLookupTimer.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public Optional<WalletAccount> findByOwner(long ownerId, AccountType type) {
        List<WalletAccount> list = jdbc.query(
                "SELECT * FROM wallet_account WHERE owner_id = ? AND owner_type = ?",
                MAPPER, ownerId, type.name());
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public Map<Long, WalletAccount> lockAccountsInOrder(long... accountIds) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("wallet account locks require an active transaction");
        }
        long started = System.nanoTime();
        try {
            long[] ordered = Arrays.stream(accountIds).distinct().sorted().toArray();
            Map<Long, WalletAccount> locked = new LinkedHashMap<>();
            if (ordered.length == 0) return locked;

            // One indexed SELECT locks every account in a deterministic order. The old
            // implementation issued one round trip per account (three for settlement),
            // extending the time that the shared escrow/commission rows stayed locked.
            String placeholders = String.join(",", java.util.Collections.nCopies(ordered.length, "?"));
            String sql = "SELECT * FROM wallet_account WHERE id IN (" + placeholders + ")"
                    + " ORDER BY id FOR UPDATE";
            Object[] args = Arrays.stream(ordered).boxed().toArray();
            for (WalletAccount account : jdbc.query(sql, MAPPER, args)) {
                locked.put(account.id(), account);
            }
            return locked;
        } finally {
            accountLockTimer.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 带条件的 CAS 转出，一条语句同时完成"判断余额是否够"和"扣减"。
     *
     * P3 修正：原实现是 available -= amount 同时 frozen += amount（预授权语义），
     * 与"资金真实转入托管账户"叠加后，同一笔钱在快照里被算了两次，
     * 全局 SUM(available+frozen) 凭空增加，且账户总额不变却记了 DEBIT 流水。
     * 托管是资金转移，所以这里只减 available，与 DEBIT 流水一一对应。
     *
     * 普通 SELECT 后再 UPDATE 会有检查-使用竞态。过账路径先 SELECT FOR UPDATE
     * 锁定账户，这里的 WHERE 条件仍作为数据库层余额下限的最后防线。
     */
    @Override
    public int casDebit(long accountId, Money amount) {
        return jdbc.update("""
                UPDATE wallet_account
                   SET available = available - ?,
                       version = version + 1
                 WHERE id = ? AND available >= ?
                """, amount.cents(), accountId, amount.cents());
    }

    @Override
    public int casCredit(long accountId, Money amount) {
        return jdbc.update("""
                UPDATE wallet_account
                   SET available = available + ?, version = version + 1
                 WHERE id = ?
                """, amount.cents(), accountId);
    }

    @Override
    public void applyAccountUpdates(List<WalletRepository.AccountUpdate> updates) {
        if (updates == null || updates.isEmpty()) return;
        int[][] counts = jdbc.batchUpdate("""
                UPDATE wallet_account
                   SET available = ?, version = ?
                 WHERE id = ? AND available = ? AND version = ?
                """, updates, updates.size(), (ps, update) -> {
            ps.setLong(1, update.availableCents());
            ps.setLong(2, update.version());
            ps.setLong(3, update.accountId());
            ps.setLong(4, update.expectedAvailableCents());
            ps.setLong(5, update.expectedVersion());
        });
        for (int[] batch : counts) {
            for (int count : batch) {
                // Connector/J may report SUCCESS_NO_INFO (-2) when
                // rewriteBatchedStatements is enabled; the DB still executed
                // every element of the batch.  A zero count remains a guard
                // failure because all rows were locked and should match.
                if (count != 1 && count != Statement.SUCCESS_NO_INFO) {
                    throw new IllegalStateException("wallet account batch update lost its lock or version");
                }
            }
        }
    }

    @Override
    public void insertLedger(LedgerEntry entry) {
        jdbc.update("""
                INSERT INTO wallet_ledger (id, biz_no, account_id, user_id, direction,
                                           amount, balance_after, account_version, ref_type, ref_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                entry.id(), entry.bizNo(), entry.accountId(), entry.userId(),
                entry.direction().name(), entry.amount().cents(), entry.balanceAfter().cents(),
                entry.accountVersion(), entry.refType().name(), entry.refId());
    }

    @Override
    public void insertLedgerBatch(List<LedgerEntry> entries) {
        if (entries == null || entries.isEmpty()) return;
        int[][] counts = jdbc.batchUpdate("""
                INSERT INTO wallet_ledger (id, biz_no, account_id, user_id, direction,
                                           amount, balance_after, account_version, ref_type, ref_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, entries, entries.size(), (ps, entry) -> {
            ps.setLong(1, entry.id());
            ps.setString(2, entry.bizNo());
            ps.setLong(3, entry.accountId());
            ps.setLong(4, entry.userId());
            ps.setString(5, entry.direction().name());
            ps.setLong(6, entry.amount().cents());
            ps.setLong(7, entry.balanceAfter().cents());
            ps.setLong(8, entry.accountVersion());
            ps.setString(9, entry.refType().name());
            ps.setLong(10, entry.refId());
        });
        for (int[] batch : counts) {
            for (int count : batch) {
                if (count != 1 && count != Statement.SUCCESS_NO_INFO) {
                    throw new IllegalStateException("wallet ledger batch insert affected unexpected rows");
                }
            }
        }
    }

    @Override
    public void insertEscrow(EscrowOrder order) {
        jdbc.update("""
                INSERT INTO escrow_order (id, campus_id, errand_id, publisher_id, amount, status)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                order.id(), order.campusId(), order.errandId(), order.publisherId(),
                order.amount().cents(), order.status().name());
    }

    @Override
    public boolean escrowExists(long campusId, long errandId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM escrow_order WHERE campus_id = ? AND errand_id = ?",
                Integer.class, campusId, errandId);
        return n != null && n > 0;
    }

    @Override
    public Optional<WalletAccount> findById(long accountId) {
        List<WalletAccount> list = jdbc.query(
                "SELECT * FROM wallet_account WHERE id = ?", MAPPER, accountId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public Optional<EscrowOrder> findEscrowByErrandId(long campusId, long errandId) {
        List<EscrowOrder> list = jdbc.query("""
                SELECT id, campus_id, errand_id, publisher_id, amount, status
                  FROM escrow_order WHERE campus_id = ? AND errand_id = ?
                """, (rs, n) -> new EscrowOrder(
                        rs.getLong("id"), rs.getLong("campus_id"),
                        rs.getLong("errand_id"), rs.getLong("publisher_id"),
                        Money.ofCents(rs.getLong("amount")),
                        EscrowOrder.EscrowStatus.valueOf(rs.getString("status"))), campusId, errandId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 托管单状态 CAS：并发重复结算的第一道闸门。
     * 两个线程同时结算同一任务，只有一个能把 HELD 改成 RELEASED，
     * 另一个 affectedRows=0，直接判定为重复请求。
     */
    @Override
    public int casEscrowStatus(long campusId, long errandId, EscrowOrder.EscrowStatus from, EscrowOrder.EscrowStatus to) {
        return jdbc.update("""
                UPDATE escrow_order SET status = ? WHERE campus_id = ? AND errand_id = ? AND status = ?
                """, to.name(), campusId, errandId, from.name());
    }

    @Override
    public boolean ledgerExists(String bizNo) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wallet_ledger WHERE biz_no = ?", Integer.class, bizNo);
        return n != null && n > 0;
    }
}
