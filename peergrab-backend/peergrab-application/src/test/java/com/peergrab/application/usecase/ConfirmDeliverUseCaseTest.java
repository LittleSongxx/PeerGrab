package com.peergrab.application.usecase;

import com.peergrab.domain.errand.model.Errand;
import com.peergrab.domain.errand.model.ErrandStatus;
import com.peergrab.domain.errand.model.ErrandType;
import com.peergrab.domain.errand.ports.ErrandRepository;
import com.peergrab.domain.grab.ports.CandidateQueuePort;
import com.peergrab.domain.notify.ports.RealtimeNotifier;
import com.peergrab.shared.BizException;
import com.peergrab.shared.ErrorCode;
import com.peergrab.shared.Money;
import com.peergrab.shared.SnowflakeIdGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfirmDeliverUseCaseTest {

    private static final long OWNER = 2001L;
    private static final long OTHER = 2002L;

    @Test
    @DisplayName("重复确认只有当前抢中者幂等成功，无关用户不能被当成成功")
    void repeat_confirm_is_scoped_to_current_runner() {
        ErrandRepository errands = mock(ErrandRepository.class);
        Errand accepted = Errand.rehydrate(7L, 1L, 1001L, ErrandType.DELIVERY, "任务",
                Money.ofCents(100), 1, OWNER, ErrandStatus.ACCEPTED, 1, 0, 2L, Instant.now());
        when(errands.findById(7L)).thenReturn(Optional.of(accepted));
        ConfirmErrandUseCase useCase = new ConfirmErrandUseCase(errands,
                mock(CacheEvictSupport.class), mock(RealtimeNotifier.class), mock(CandidateQueuePort.class));

        assertDoesNotThrow(() -> useCase.confirm(new ConfirmErrandUseCase.Command(7L, OWNER)));

        BizException ex = assertThrows(BizException.class,
                () -> useCase.confirm(new ConfirmErrandUseCase.Command(7L, OTHER)));
        assertEquals(ErrorCode.NOT_CURRENT_GRABBER, ex.code());
        verify(errands, times(2)).findById(7L);
    }

    @Test
    @DisplayName("重复送达按幂等成功处理，不再抛非法状态流转")
    void repeat_deliver_is_idempotent() {
        ErrandRepository errands = mock(ErrandRepository.class);
        com.peergrab.domain.errand.ports.LocalMessageRepository messages =
                mock(com.peergrab.domain.errand.ports.LocalMessageRepository.class);
        Errand delivered = Errand.rehydrate(8L, 1L, 1001L, ErrandType.DELIVERY, "任务",
                Money.ofCents(100), 1, OWNER, ErrandStatus.DELIVERED, 1, 0, 3L,
                Instant.now(), Instant.now(), null, Instant.now().plusSeconds(3600), 0);
        when(errands.findById(8L)).thenReturn(Optional.of(delivered));
        DeliverErrandUseCase useCase = new DeliverErrandUseCase(errands, messages,
                new SnowflakeIdGenerator(1), mock(CacheEvictSupport.class),
                mock(RealtimeNotifier.class), "errand-auto-settle");

        assertDoesNotThrow(() -> useCase.deliver(8L, OWNER));

        verifyNoInteractions(messages);
    }
}
