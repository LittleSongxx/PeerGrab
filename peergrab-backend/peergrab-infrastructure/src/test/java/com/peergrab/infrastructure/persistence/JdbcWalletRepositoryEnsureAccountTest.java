package com.peergrab.infrastructure.persistence;

import com.peergrab.domain.wallet.model.AccountType;
import com.peergrab.domain.wallet.model.WalletAccount;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * ensureAccount 的幂等开户语义（H2 / MySQL 兼容模式）。
 *
 * <p>“每任务独立账户”要求发布事务可以重复执行 ensureAccount 而不重复开户：
 * uk_owner(owner_id, owner_type) + INSERT ... ON DUPLICATE KEY UPDATE 必须保证
 * 重复调用后仍然是同一行；开通只写零余额账户，绝不覆盖已有余额与版本。</p>
 */
class JdbcWalletRepositoryEnsureAccountTest {

    private JdbcTemplate freshJdbc() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:wallet_ensure_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE wallet_account (
                  id BIGINT PRIMARY KEY, owner_id BIGINT NOT NULL, owner_type VARCHAR(16) NOT NULL,
                  available BIGINT NOT NULL, frozen BIGINT NOT NULL, version BIGINT NOT NULL
                )
                """);
        jdbc.execute("CREATE UNIQUE INDEX uk_owner ON wallet_account(owner_id, owner_type)");
        return jdbc;
    }

    private JdbcWalletRepository repository(JdbcTemplate jdbc) {
        return new JdbcWalletRepository(jdbc, new SimpleMeterRegistry());
    }

    @Test
    void repeatedEnsureReturnsTheSameZeroBalanceAccount() {
        JdbcTemplate jdbc = freshJdbc();
        JdbcWalletRepository repository = repository(jdbc);

        WalletAccount first = repository.ensureAccount(9001, AccountType.ESCROW);
        WalletAccount second = repository.ensureAccount(9001, AccountType.ESCROW);
        WalletAccount third = repository.ensureAccount(9001, AccountType.ESCROW);

        assertEquals(first.id(), second.id(), "重复 ensure 必须返回同一账户 ID");
        assertEquals(first.id(), third.id(), "重复 ensure 必须返回同一账户 ID");
        assertEquals(9001L, first.ownerId());
        assertEquals(AccountType.ESCROW, first.type());
        assertEquals(0L, first.available().cents(), "开户只写零余额账户");
        assertEquals(0L, first.frozen().cents());
        assertEquals(0L, first.version());
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM wallet_account
                 WHERE owner_id = 9001 AND owner_type = 'ESCROW'
                """, Integer.class), "重复 ensure 后仍然只能有一行");
    }

    @Test
    void sameErrandOwnsAnEscrowAndACommissionAccount() {
        JdbcTemplate jdbc = freshJdbc();
        JdbcWalletRepository repository = repository(jdbc);

        WalletAccount escrow = repository.ensureAccount(9001, AccountType.ESCROW);
        WalletAccount commission = repository.ensureAccount(9001, AccountType.COMMISSION);

        assertEquals(9001L, escrow.ownerId());
        assertEquals(9001L, commission.ownerId());
        assertNotEquals(escrow.id(), commission.id(),
                "同一 errandId 的 ESCROW/COMMISSION 是两行，由 (owner_id, owner_type) 区分");
        assertEquals(2, jdbc.queryForObject("""
                SELECT COUNT(*) FROM wallet_account WHERE owner_id = 9001
                """, Integer.class));
    }

    @Test
    void ensureDoesNotResetExistingBalanceOrVersion() {
        JdbcTemplate jdbc = freshJdbc();
        JdbcWalletRepository repository = repository(jdbc);
        jdbc.update("INSERT INTO wallet_account VALUES (77, 1001, 'USER', 5000, 0, 3)");

        WalletAccount account = repository.ensureAccount(1001, AccountType.USER);

        assertEquals(77L, account.id(), "已存在账户直接复用，不新建行");
        assertEquals(5000L, account.available().cents(), "ensure 不得覆盖已有余额");
        assertEquals(3L, account.version(), "ensure 不得重置版本号");
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM wallet_account WHERE owner_id = 1001 AND owner_type = 'USER'
                """, Integer.class));
    }

    @Test
    void ensureUserAccountDelegatesToTheUserOwnedAccount() {
        JdbcTemplate jdbc = freshJdbc();
        JdbcWalletRepository repository = repository(jdbc);

        repository.ensureUserAccount(2001);
        WalletAccount ensured = repository.ensureAccount(2001, AccountType.USER);

        assertEquals(2001L, ensured.ownerId());
        assertEquals(AccountType.USER, ensured.type());
        assertEquals(ensured.id(), repository.findByOwner(2001, AccountType.USER).orElseThrow().id());
    }
}
