package com.peergrab.application.usecase;

import com.peergrab.domain.credit.ports.CreditRankingPort;
import com.peergrab.domain.credit.ports.CreditRepository;
import com.peergrab.domain.errand.model.Errand;
import com.peergrab.domain.errand.model.ErrandStatus;
import com.peergrab.domain.errand.model.ErrandType;
import com.peergrab.domain.errand.ports.ErrandRepository;
import com.peergrab.domain.notify.ports.RealtimeNotifier;
import com.peergrab.domain.wallet.ports.FundAuditPort;
import com.peergrab.domain.wallet.ports.FundEventPort;
import com.peergrab.domain.wallet.ports.WalletRepository;
import com.peergrab.shared.Money;
import com.peergrab.shared.SnowflakeIdGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class SettleErrandUseCaseTest {

    @Test
    @DisplayName("自动结算消息早于数据库截止时间时返回 NOT_DUE，不触碰账务")
    void earlyAutoSettleMessageIsNotDue() {
        ErrandRepository errands = mock(ErrandRepository.class);
        WalletRepository wallets = mock(WalletRepository.class);
        Instant deadline = Instant.now().plusSeconds(3600);
        Errand delivered = Errand.rehydrate(7L, 1L, 1001L, ErrandType.DELIVERY, "测试",
                Money.ofCents(1000), 1, 2001L, ErrandStatus.DELIVERED, 1, 0, 3L,
                Instant.now().minusSeconds(60), Instant.now().minusSeconds(30), null, deadline, 0);
        when(errands.findById(7L)).thenReturn(Optional.of(delivered));

        SettleErrandUseCase useCase = new SettleErrandUseCase(
                errands, wallets, mock(FundAuditPort.class), mock(FundEventPort.class),
                new SnowflakeIdGenerator(1), mock(CacheEvictSupport.class),
                mock(CreditRepository.class), mock(CreditRankingPort.class),
                mock(TransactionTemplate.class), mock(RealtimeNotifier.class), 0.05);

        assertEquals(SettleErrandUseCase.Result.NOT_DUE,
                useCase.settle(7L, Errand.SYSTEM_OPERATOR));
        verifyNoInteractions(wallets);
    }
}
