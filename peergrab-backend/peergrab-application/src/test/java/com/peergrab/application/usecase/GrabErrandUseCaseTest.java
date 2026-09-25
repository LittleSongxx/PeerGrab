package com.peergrab.application.usecase;

import com.peergrab.domain.credit.model.CreditEvent;
import com.peergrab.domain.credit.model.CreditScore;
import com.peergrab.domain.credit.ports.CreditRepository;
import com.peergrab.domain.errand.model.Errand;
import com.peergrab.domain.errand.model.ErrandStatus;
import com.peergrab.domain.errand.model.ErrandType;
import com.peergrab.domain.errand.ports.ErrandQueryPort;
import com.peergrab.domain.errand.ports.ErrandRepository;
import com.peergrab.domain.grab.model.SlotOutcome;
import com.peergrab.domain.grab.model.GrabRecord;
import com.peergrab.domain.grab.ports.CandidateQueuePort;
import com.peergrab.domain.grab.ports.GrabRateLimiterPort;
import com.peergrab.domain.grab.ports.GrabRecordRepository;
import com.peergrab.domain.grab.ports.GrabSlotPort;
import com.peergrab.domain.notify.ports.RealtimeNotifier;
import com.peergrab.shared.ErrorCode;
import com.peergrab.shared.SnowflakeIdGenerator;
import com.peergrab.shared.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GrabErrandUseCaseTest {

    @Test
    @DisplayName("热点限流拒绝后直接返回限流码，不再访问信用分和名额裁决")
    void grab_returns_rate_limited_before_downstream_calls() {
        RejectingRateLimiter rateLimiter = new RejectingRateLimiter();
        CountingCreditRepository creditRepository = new CountingCreditRepository();
        CountingGrabSlotPort grabSlotPort = new CountingGrabSlotPort();
        GrabErrandUseCase useCase = new GrabErrandUseCase(
                grabSlotPort,
                new EmptyGrabRecordRepository(),
                new NoopCandidateQueue(),
                new EmptyErrandRepository(),
                null,
                new SnowflakeIdGenerator(1),
                null,
                null,
                creditRepository,
                new NoopErrandQueryPort(),
                40,
                5,
                new NoopRealtimeNotifier(),
                rateLimiter);

        GrabErrandUseCase.Result result = useCase.grab(
                new GrabErrandUseCase.Command(10001L, 2001L, "req-limited"));

        assertFalse(result.grabbed());
        assertEquals(ErrorCode.GRAB_RATE_LIMITED, result.code());
        assertEquals(1, rateLimiter.calls);
        assertEquals(0, creditRepository.scoreCalls, "限流后不应查询信用分");
        assertEquals(0, grabSlotPort.tryAcquireCalls, "限流后不应打 Redis Lua");
    }

    @Test
    @DisplayName("发单人自抢在 Redis 扣名额前被拒绝")
    void publisher_cannot_grab_own_errand() {
        CountingCreditRepository creditRepository = new CountingCreditRepository();
        CountingGrabSlotPort grabSlotPort = new CountingGrabSlotPort();
        Errand ownErrand = Errand.draft(10001L, 1L, 1001L, ErrandType.DELIVERY,
                "自抢测试", Money.ofCents(100), 1);
        GrabErrandUseCase useCase = new GrabErrandUseCase(
                grabSlotPort, new EmptyGrabRecordRepository(), new NoopCandidateQueue(),
                new EmptyErrandRepository(ownErrand),
                null, new SnowflakeIdGenerator(1), null, null, creditRepository,
                new NoopErrandQueryPort(), 40, 5, new NoopRealtimeNotifier(), (id, runner) -> true);

        var result = useCase.grab(new GrabErrandUseCase.Command(10001L, 1001L, "self-grab"));
        assertEquals(ErrorCode.SELF_GRAB_FORBIDDEN, result.code());
        assertEquals(0, creditRepository.scoreCalls);
        assertEquals(0, grabSlotPort.tryAcquireCalls);
    }

    @Test
    @DisplayName("Redis 重复预占位不能冒充数据库抢中结果")
    void duplicate_reservation_is_not_reported_as_success() {
        var slot = mock(GrabSlotPort.class);
        var records = mock(GrabRecordRepository.class);
        var errands = mock(ErrandRepository.class);
        var step = mock(GrabTransactionalStep.class);
        var errand = publishedErrand();
        when(errands.findById(errand.id())).thenReturn(Optional.of(errand));
        when(records.findRunnerByRequestId(1L, errand.id(), "same-request")).thenReturn(Optional.empty());
        when(slot.tryAcquire(errand.id(), 2001L, "same-request")).thenReturn(SlotOutcome.DUPLICATE_REQUEST);

        var result = useCase(slot, records, errands, step).grab(
                new GrabErrandUseCase.Command(errand.id(), 2001L, "same-request"));

        assertFalse(result.grabbed());
        assertEquals(ErrorCode.GRAB_CONFLICT, result.code());
        verifyNoInteractions(step);
    }

    @Test
    @DisplayName("持久化 requestId 绑定跑腿，跨用户重放不返回成功")
    void persisted_request_id_is_bound_to_runner() {
        var slot = mock(GrabSlotPort.class);
        var records = mock(GrabRecordRepository.class);
        var errands = mock(ErrandRepository.class);
        var errand = Errand.rehydrate(10001L, 1L, 1001L, ErrandType.DELIVERY,
                "测试任务", Money.ofCents(100), 1, 2001L, ErrandStatus.LOCKED,
                1, 0, 2L, java.time.Instant.now());
        when(errands.findById(errand.id())).thenReturn(Optional.of(errand));
        when(records.findRunnerByRequestId(1L, errand.id(), "used-request")).thenReturn(Optional.of(2001L));

        var result = useCase(slot, records, errands, mock(GrabTransactionalStep.class)).grab(
                new GrabErrandUseCase.Command(errand.id(), 2002L, "used-request"));

        assertEquals(ErrorCode.DUPLICATE_REQUEST, result.code());
        verifyNoInteractions(slot);
    }

    @Test
    @DisplayName("Redis 名额键丢失时由数据库 CAS 抢中，后置消息失败不误报失败")
    void missing_slot_uses_database_cas_and_keeps_committed_success() {
        var slot = mock(GrabSlotPort.class);
        var records = mock(GrabRecordRepository.class);
        var errands = mock(ErrandRepository.class);
        var step = mock(GrabTransactionalStep.class);
        var timeout = mock(TimeoutTransferUseCase.class);
        var errand = publishedErrand();
        when(errands.findById(errand.id())).thenReturn(Optional.of(errand));
        when(records.findRunnerByRequestId(1L, errand.id(), "fallback-request")).thenReturn(Optional.empty());
        when(slot.tryAcquire(errand.id(), 2001L, "fallback-request")).thenReturn(SlotOutcome.SLOT_MISSING);
        when(step.lockAndRecord(eq(1L), eq(errand.id()), eq(2001L), anyLong(),
                anyInt(), anyInt(), anyLong(), any(), eq("fallback-request"))).thenReturn(true);
        doThrow(new IllegalStateException("message store unavailable"))
                .when(timeout).scheduleFirstTimeout(eq(errand.id()), anyInt(), anyLong());

        var result = useCase(slot, records, errands, step, timeout).grab(
                new GrabErrandUseCase.Command(errand.id(), 2001L, "fallback-request"));

        assertTrue(result.grabbed());
        verify(slot, never()).rollback(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("Redis 陈旧满额且没有在途预占位时允许 DB CAS 恢复")
    void stale_full_slot_uses_database_cas() {
        var slot = mock(GrabSlotPort.class);
        var records = mock(GrabRecordRepository.class);
        var errands = mock(ErrandRepository.class);
        var step = mock(GrabTransactionalStep.class);
        var errand = publishedErrand();
        when(errands.findById(errand.id())).thenReturn(Optional.of(errand));
        when(slot.tryAcquire(errand.id(), 2001L, "stale-slot")).thenReturn(SlotOutcome.SLOT_FULL);
        when(slot.reservationPending(errand.id())).thenReturn(false);
        when(step.lockAndRecord(eq(1L), eq(errand.id()), eq(2001L), anyLong(),
                anyInt(), anyInt(), anyLong(), any(), eq("stale-slot"))).thenReturn(true);

        var result = useCase(slot, records, errands, step).grab(
                new GrabErrandUseCase.Command(errand.id(), 2001L, "stale-slot"));

        assertTrue(result.grabbed());
        verify(slot).invalidate(errand.id());
    }

    private static Errand publishedErrand() {
        Errand errand = Errand.draft(10001L, 1L, 1001L, ErrandType.DELIVERY,
                "测试任务", Money.ofCents(100), 1);
        errand.publish(0L);
        return errand;
    }

    private static GrabErrandUseCase useCase(GrabSlotPort slot, GrabRecordRepository records,
                                            ErrandRepository errands, GrabTransactionalStep step) {
        return useCase(slot, records, errands, step, mock(TimeoutTransferUseCase.class));
    }

    private static GrabErrandUseCase useCase(GrabSlotPort slot, GrabRecordRepository records,
                                            ErrandRepository errands, GrabTransactionalStep step,
                                            TimeoutTransferUseCase timeout) {
        var credit = mock(CreditRepository.class);
        when(credit.scoreOf(anyLong())).thenReturn(60);
        return new GrabErrandUseCase(slot, records, new NoopCandidateQueue(), errands,
                step, new SnowflakeIdGenerator(1), timeout, mock(CacheEvictSupport.class),
                credit, new NoopErrandQueryPort(), 40, 5,
                new NoopRealtimeNotifier(), (id, runner) -> true);
    }

    private static final class RejectingRateLimiter implements GrabRateLimiterPort {
        int calls;

        @Override
        public boolean tryPass(long errandId, long runnerId) {
            calls++;
            return false;
        }
    }

    private static final class CountingCreditRepository implements CreditRepository {
        int scoreCalls;

        @Override
        public int scoreOf(long userId) {
            scoreCalls++;
            return 60;
        }

        @Override public Optional<CreditScore> find(long userId) { return Optional.empty(); }
        @Override public boolean applyEvent(CreditEvent event) { return false; }
        @Override public List<CreditEvent> recentEvents(long userId, int days, int limit) { return List.of(); }
        @Override public int windowDelta(long userId, int windowDays) { return 0; }
        @Override public int calibrateScores(int windowDays, int limit) { return 0; }
    }

    private static final class CountingGrabSlotPort implements GrabSlotPort {
        int tryAcquireCalls;

        @Override
        public SlotOutcome tryAcquire(long errandId, long runnerId, String requestId) {
            tryAcquireCalls++;
            return SlotOutcome.NOT_GRABBABLE;
        }

        @Override public void rollback(long errandId, long runnerId, String requestId) {}
        @Override public void invalidate(long errandId) {}
        @Override public boolean reservationPending(long errandId) { return false; }
        @Override public void initSlot(long errandId, int slotTotal, long ttlSeconds) {}
        @Override public long remainingSlot(long errandId) { return 0; }
    }

    private static final class NoopCandidateQueue implements CandidateQueuePort {
        @Override public void offer(long errandId, long runnerId, double score) {}
        @Override public Optional<Candidate> pollBest(long errandId) { return Optional.empty(); }
        @Override public long size(long errandId) { return 0; }
        @Override public void clear(long errandId) {}
    }

    private static final class EmptyGrabRecordRepository implements GrabRecordRepository {
        @Override public void insert(GrabRecord record) {}
        @Override public int countGrabbed(long campusId, long errandId) { return 0; }
        @Override public Optional<Long> findRunnerByRequestId(long campusId, long errandId, String requestId) {
            return Optional.empty();
        }
    }

    private static final class EmptyErrandRepository implements ErrandRepository {
        private final Errand errand;

        EmptyErrandRepository() { this(null); }
        EmptyErrandRepository(Errand errand) { this.errand = errand; }

        @Override public void insert(Errand errand) {}
        @Override public Optional<Errand> findById(long errandId) { return Optional.ofNullable(errand); }
        @Override public int casLockForRunner(long errandId, long runnerId, long expectedVersion) { return 0; }
        @Override public int casPublish(long errandId, long expectedVersion) { return 0; }
        @Override public void appendStatusLog(long errandId, ErrandStatus from, ErrandStatus to, int round, long operatorId) {}
        @Override public int casAccept(long errandId, long runnerId, long expectedVersion) { return 0; }
        @Override public int casTransferToNext(long errandId, long nextRunnerId, long expectedVersion, int expectedRound) { return 0; }
        @Override public int casRevertToPublished(long errandId, long expectedVersion, int expectedRound) { return 0; }
        @Override public List<Errand> findConfirmTimeout(long timeoutSeconds, int limit) { return List.of(); }
        @Override public int casPickUp(long errandId, long runnerId, long expectedVersion) { return 0; }
        @Override public int casDeliver(long errandId, long runnerId, long expectedVersion) { return 0; }
        @Override public int casSettle(long errandId, long expectedVersion) { return 0; }
        @Override public int casRefundFromDispute(long errandId, long expectedVersion) { return 0; }
        @Override public int casCancel(long errandId, long expectedVersion) { return 0; }
        @Override public int casDispute(long errandId, long expectedVersion) { return 0; }
        @Override public int casSettleFromDispute(long errandId, long expectedVersion) { return 0; }
        @Override public List<Errand> findAutoSettleDue(long autoSettleSeconds, int limit) { return List.of(); }
        @Override public List<Errand> findAutoSettleDueAfter(long autoSettleSeconds, java.time.Instant afterAt, long afterId, int limit) { return List.of(); }
        @Override public List<Errand> findConfirmTimeoutAfter(long timeoutSeconds, java.time.Instant afterAt, long afterId, int limit) { return List.of(); }
    }

    private static final class NoopErrandQueryPort implements ErrandQueryPort {
        @Override public List<Errand> list(long campusId, String status, int page, int size) { return List.of(); }
        @Override public List<CursorItem> listByCursor(long campusId, String status, java.time.Instant beforeCreatedAt, Long beforeId, int size) { return List.of(); }
        @Override public List<Errand> listByPublisher(long publisherId, int page, int size) { return List.of(); }
        @Override public List<Errand> listByRunner(long runnerId, int page, int size) { return List.of(); }
        @Override public List<StatusChange> statusLog(long campusId, long errandId) { return List.of(); }
        @Override public List<Long> sampleIds(int limit) { return List.of(); }
        @Override public List<Long> scanIdsAfter(long afterId, int limit) { return List.of(); }
        @Override public int countOngoingByRunner(long runnerId) { return 0; }
    }

    private static final class NoopRealtimeNotifier implements RealtimeNotifier {
        @Override public void errandStatusChanged(long errandId, long publisherId, Long grabberId, String status, int round) {}
        @Override public void notificationArrived(long userId, long errandId, String type, String content) {}
        @Override public void creditChanged(long userId, int newScore, int delta, String reason) {}
    }
}
