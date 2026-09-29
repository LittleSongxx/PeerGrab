package com.peergrab.infrastructure.persistence;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.List;
import com.peergrab.domain.wallet.model.AccountType;
import com.peergrab.domain.wallet.ports.WalletRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcWalletRepositoryLockTest {

    @Test
    void locksDistinctAccountsWithOneOrderedSelectAndKeepsMissingRowsAbsent() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:wallet_lock_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE wallet_account (
                  id BIGINT PRIMARY KEY, owner_id BIGINT NOT NULL, owner_type VARCHAR(16) NOT NULL,
                  available BIGINT NOT NULL, frozen BIGINT NOT NULL, version BIGINT NOT NULL
                )
                """);
        jdbc.update("INSERT INTO wallet_account VALUES (1,-1,'ESCROW',100,0,7)");
        jdbc.update("INSERT INTO wallet_account VALUES (2,-2,'COMMISSION',0,0,3)");
        jdbc.update("INSERT INTO wallet_account VALUES (9,2001,'USER',0,0,4)");

        var repository = new JdbcWalletRepository(jdbc, new SimpleMeterRegistry());
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        Map<Long, ?> locked = transaction.execute(status ->
                repository.lockAccountsInOrder(9, 2, 9, 1, 88));

        assertEquals(java.util.List.of(1L, 2L, 9L), locked.keySet().stream().toList());
        assertTrue(locked.containsKey(1L));
        assertTrue(locked.containsKey(9L));
        assertEquals(3, locked.size());
    }

    @Test
    void batchesOwnerLookupThroughTheOwnerUniqueIndex() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:wallet_owner_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE wallet_account (
                  id BIGINT PRIMARY KEY, owner_id BIGINT NOT NULL, owner_type VARCHAR(16) NOT NULL,
                  available BIGINT NOT NULL, frozen BIGINT NOT NULL, version BIGINT NOT NULL
                )
                """);
        jdbc.execute("CREATE UNIQUE INDEX uk_owner ON wallet_account(owner_id, owner_type)");
        jdbc.update("INSERT INTO wallet_account VALUES (1,-1,'ESCROW',100,0,7)");
        jdbc.update("INSERT INTO wallet_account VALUES (2,-2,'COMMISSION',0,0,3)");
        jdbc.update("INSERT INTO wallet_account VALUES (9,2001,'USER',0,0,4)");

        var repository = new JdbcWalletRepository(jdbc, new SimpleMeterRegistry());
        var escrow = new WalletRepository.OwnerRef(-1, AccountType.ESCROW);
        var runner = new WalletRepository.OwnerRef(2001, AccountType.USER);
        var accounts = repository.findByOwners(List.of(escrow, runner, escrow));

        assertEquals(List.of(escrow, runner), accounts.keySet().stream().toList());
        assertEquals(1L, accounts.get(escrow).id());
        assertEquals(9L, accounts.get(runner).id());
    }

    @Test
    void appliesLockedAccountBatchWithExpectedVersionGuard() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:wallet_batch_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE wallet_account (
                  id BIGINT PRIMARY KEY, owner_id BIGINT NOT NULL, owner_type VARCHAR(16) NOT NULL,
                  available BIGINT NOT NULL, frozen BIGINT NOT NULL, version BIGINT NOT NULL
                )
                """);
        jdbc.update("INSERT INTO wallet_account VALUES (1,-1,'ESCROW',1000,0,7)");
        jdbc.update("INSERT INTO wallet_account VALUES (9,2001,'USER',0,0,4)");

        var repository = new JdbcWalletRepository(jdbc, new SimpleMeterRegistry());
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.executeWithoutResult(status -> repository.applyAccountUpdates(List.of(
                new WalletRepository.AccountUpdate(1, 1000, 700, 7, 8),
                new WalletRepository.AccountUpdate(9, 0, 300, 4, 5))));

        assertEquals(700L, jdbc.queryForObject("SELECT available FROM wallet_account WHERE id=1", Long.class));
        assertEquals(8L, jdbc.queryForObject("SELECT version FROM wallet_account WHERE id=1", Long.class));
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status ->
                repository.applyAccountUpdates(List.of(
                        new WalletRepository.AccountUpdate(1, 1000, 400, 7, 9)))));
    }
}
