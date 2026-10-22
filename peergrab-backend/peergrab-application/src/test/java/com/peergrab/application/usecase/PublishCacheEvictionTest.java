package com.peergrab.application.usecase;

import com.peergrab.domain.errand.model.Errand;
import com.peergrab.domain.errand.model.ErrandType;
import com.peergrab.domain.errand.ports.CacheEvictDelayPort;
import com.peergrab.domain.errand.ports.ErrandCachePort;
import com.peergrab.domain.errand.ports.ErrandRepository;
import com.peergrab.domain.errand.ports.PublishRequestRepository;
import com.peergrab.domain.grab.ports.GrabSlotPort;
import com.peergrab.domain.wallet.model.AccountType;
import com.peergrab.domain.wallet.model.WalletAccount;
import com.peergrab.domain.wallet.ports.WalletRepository;
import com.peergrab.shared.BizException;
import com.peergrab.shared.ErrorCode;
import com.peergrab.shared.Money;
import com.peergrab.shared.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PublishCacheEvictionTest {

    @Test
    void rejectsTrailingSpaceRequestIdBeforeClaimingOrDebiting() {
        PublishRequestRepository requests = mock(PublishRequestRepository.class);
        WalletRepository wallets = mock(WalletRepository.class);
        PublishErrandUseCase useCase = new PublishErrandUseCase(
                mock(ErrandRepository.class), requests, wallets, mock(GrabSlotPort.class),
                new SnowflakeIdGenerator(1), mock(ErrandCachePort.class),
                mock(CacheEvictSupport.class));

        BizException error = assertThrows(BizException.class, () -> useCase.publish(
                new PublishErrandUseCase.Command(1, 1001, ErrandType.DELIVERY,
                        "same key", 100, 1, "request-id ")));
        assertEquals(ErrorCode.INVALID_ARGUMENT, error.code());
        verifyNoInteractions(requests, wallets);
    }

    @Test
    void previouslyCachedEmptyDetailIsEvictedOnlyAfterPublishCommits() {
        ErrandRepository errands = mock(ErrandRepository.class);
        PublishRequestRepository requests = mock(PublishRequestRepository.class);
        WalletRepository wallets = mock(WalletRepository.class);
        GrabSlotPort slots = mock(GrabSlotPort.class);
        ErrandCachePort cache = mock(ErrandCachePort.class);
        CacheEvictDelayPort delay = mock(CacheEvictDelayPort.class);
        CacheEvictSupport cacheEvict = new CacheEvictSupport(cache, delay, 500, false);
        PublishErrandUseCase useCase = new PublishErrandUseCase(errands, requests, wallets, slots,
                new SnowflakeIdGenerator(1), cache, cacheEvict);

        when(requests.claimAndLock(eq(1001L), anyString(), anyString())).thenAnswer(
                invocation -> new PublishRequestRepository.Claim(invocation.getArgument(2), null));
        when(requests.complete(eq(1001L), anyString(), anyLong())).thenReturn(1);
        when(wallets.findByOwner(1001, AccountType.USER))
                .thenReturn(Optional.of(account(1001, 1001, AccountType.USER, 1_000)));
        // 发布时按 errandId 幂等开通每单 ESCROW/COMMISSION 账户（ownerId = errandId），
        // 托管户接收悬赏金，佣金户留待结算/仲裁入账，二者都不再是全局固定系统户。
        when(wallets.ensureAccount(anyLong(), eq(AccountType.ESCROW))).thenAnswer(invocation ->
                account(1, invocation.<Long>getArgument(0), AccountType.ESCROW, 0));
        when(wallets.ensureAccount(anyLong(), eq(AccountType.COMMISSION))).thenAnswer(invocation ->
                account(2, invocation.<Long>getArgument(0), AccountType.COMMISSION, 0));
        when(wallets.lockAccountsInOrder(any(long[].class))).thenAnswer(invocation -> {
            // vararg long... 在 Mockito 调用点被展开成多个 Long 实参，逐个还原账户 ID。
            Map<Long, WalletAccount> locked = new java.util.LinkedHashMap<>();
            for (Object raw : invocation.getArguments()) {
                long accountId = ((Number) raw).longValue();
                locked.put(accountId, accountId == 1L
                        ? account(1, 0, AccountType.ESCROW, 0)
                        : account(1001, 1001, AccountType.USER, 1_000));
            }
            return locked;
        });
        when(wallets.casDebit(eq(1001L), any())).thenReturn(1);
        when(wallets.casCredit(eq(1L), any())).thenReturn(1);
        when(errands.casPublish(anyLong(), eq(0L))).thenReturn(1);
        when(errands.findById(anyLong())).thenAnswer(invocation -> {
            long id = invocation.getArgument(0);
            Errand errand = Errand.draft(id, 1, 1001, ErrandType.DELIVERY,
                    "cache eviction", Money.ofCents(100), 1);
            errand.publish(0);
            return Optional.of(errand);
        });
        when(cache.registerExisting(anyLong())).thenReturn(true);
        doThrow(new IllegalStateException("Redis unavailable"))
                .when(slots).initSlot(anyLong(), anyInt(), anyLong());

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            long id = useCase.publish(new PublishErrandUseCase.Command(
                    1, 1001, ErrandType.DELIVERY, "cache eviction", 100, 1)).errandId();
            verify(cache, never()).evict(id);
            verify(cache, never()).registerExisting(id);
            verify(slots, never()).initSlot(eq(id), anyInt(), anyLong());
            assertEquals(2, TransactionSynchronizationManager.getSynchronizations().size());
            for (TransactionSynchronization synchronization
                    : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
            // Redis failure is logged; the committed task and the remaining Bloom/cache
            // callbacks still complete, and the original publish result stays successful.
            verify(cache).evict(id);
            verify(cache).registerExisting(id);
            verify(slots).initSlot(eq(id), eq(1), anyLong());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private static WalletAccount account(long id, long owner, AccountType type, long available) {
        return new WalletAccount(id, owner, type, Money.ofCents(available), Money.ZERO, 0);
    }
}
