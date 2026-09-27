package com.peergrab.application.usecase;

import com.peergrab.domain.errand.model.Errand;
import com.peergrab.domain.credit.model.CreditEvent;
import com.peergrab.domain.credit.model.CreditEventType;
import com.peergrab.domain.credit.ports.CreditRepository;
import com.peergrab.domain.errand.model.ErrandStatus;
import com.peergrab.domain.errand.ports.ErrandRepository;
import com.peergrab.domain.errand.ports.LocalMessageRepository;
import com.peergrab.domain.errand.ports.RunnerQuotaPort;
import com.peergrab.domain.notify.ports.NotificationRepository;
import com.peergrab.shared.SnowflakeIdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 超时流转的事务性步骤，单独成 Bean。
 *
 * 为什么必须拆出来：如果把这些 @Transactional 方法留在 TimeoutTransferUseCase 里，
 * handleTimeout 调用 this.transfer() 属于同类内部自调用，会绕过 Spring 的 AOP 代理，
 * 事务注解根本不生效——这是 @Transactional 六种失效场景里最常见的一种。
 * P1 的 GrabTransactionalStep 是同样的理由。
 *
 * 另一个设计点：事务内只登记本地消息，绝不在事务里发 MQ。
 * 发 MQ 是外部 IO，放进事务会拉长事务持有时间、放大锁竞争；
 * 而且发送失败不该回滚业务。所以这里返回"待发送的消息"，由调用方在事务提交后再发。
 */
@Service
public class TimeoutTransferStep {

    private final ErrandRepository errandRepository;
    private final LocalMessageRepository localMessageRepository;
    private final SnowflakeIdGenerator idGenerator;
    private final RunnerQuotaPort runnerQuotaPort;
    private final CreditRepository creditRepository;
    private final NotificationRepository notificationRepository;
    private final int maxOngoing;

    public TimeoutTransferStep(ErrandRepository errandRepository,
                               LocalMessageRepository localMessageRepository,
                               SnowflakeIdGenerator idGenerator,
                               RunnerQuotaPort runnerQuotaPort,
                               CreditRepository creditRepository,
                               NotificationRepository notificationRepository,
                               @org.springframework.beans.factory.annotation.Value("${peergrab.credit.max-ongoing:5}") int maxOngoing) {
        this.errandRepository = errandRepository;
        this.localMessageRepository = localMessageRepository;
        this.idGenerator = idGenerator;
        this.runnerQuotaPort = runnerQuotaPort;
        this.creditRepository = creditRepository;
        this.notificationRepository = notificationRepository;
        this.maxOngoing = maxOngoing;
    }

    /** 事务执行结果：是否成功，以及事务提交后需要投递的消息（可能为空） */
    public record StepResult(boolean applied, PendingSend pendingSend, boolean quotaFull) {
        public StepResult(boolean applied, PendingSend pendingSend) {
            this(applied, pendingSend, false);
        }

        static StepResult skipped() {
            return new StepResult(false, null);
        }

        static StepResult capacityRejected() {
            return new StepResult(false, null, true);
        }
    }

    public record PendingSend(String topic, String msgKey, String payload, Instant deliverAt) {}

    /**
     * 流转给下一位候选人：CAS + 状态日志 + 下一轮超时消息登记，同一事务内完成。
     * 三者必须原子——否则会出现"已流转但没有超时保护"的黑洞，任务永久卡住。
     */
    @Transactional(rollbackFor = Exception.class)
    public StepResult transfer(Errand errand, long nextRunnerId) {
        if (!runnerQuotaPort.lockAndHasCapacity(nextRunnerId, maxOngoing)) {
            return StepResult.capacityRejected();
        }
        int affected = errandRepository.casTransferToNext(
                errand.id(), nextRunnerId, errand.version(), errand.round());
        if (affected == 0) {
            return StepResult.skipped();
        }
        int newRound = errand.round() + 1;
        errandRepository.appendStatusLog(errand.id(), ErrandStatus.LOCKED, ErrandStatus.LOCKED,
                newRound, nextRunnerId);
        notificationRepository.insertIfAbsent(idGenerator.nextId(),
                "timeout-transfer:" + errand.id() + ":" + newRound + ":runner",
                nextRunnerId, errand.id(), "TRANSFERRED",
                "任务已递补给你，请在确认时限内接单");

        // 新抢中者也有确认窗口，登记下一轮超时消息
        PendingSend send = enqueueTimeout(errand.id(), newRound, errand.version() + 1);
        return new StepResult(true, send);
    }

    /** 候选队列空，回退重新开放：名额在 SQL 里一并还回（slot_taken-1） */
    @Transactional(rollbackFor = Exception.class)
    public StepResult revert(Errand errand) {
        int affected = errandRepository.casRevertToPublished(
                errand.id(), errand.version(), errand.round());
        if (affected == 0) {
            return StepResult.skipped();
        }
        errandRepository.appendStatusLog(errand.id(), ErrandStatus.LOCKED, ErrandStatus.PUBLISHED,
                errand.round() + 1, -1L);
        if (errand.grabberId() != null) {
            creditRepository.applyEvent(new CreditEvent(
                    idGenerator.nextId(), CreditEvent.revertBizNo(errand.id(), errand.round()),
                    errand.grabberId(), CreditEventType.GRAB_TIMEOUT_REVERT,
                    CreditEventType.GRAB_TIMEOUT_REVERT.delta(), "ERRAND", errand.id(),
                    Instant.now()));
        }
        notificationRepository.insertIfAbsent(idGenerator.nextId(),
                "timeout-revert:" + errand.id() + ":" + errand.round() + ":publisher",
                errand.publisherId(), errand.id(), "REVERTED",
                "上一位跑腿未及时确认，任务已重新开放");
        return new StepResult(true, null);
    }

    /**
     * 抢单成功后登记首轮超时消息，由 GrabErrandUseCase 在抢单事务提交后调用。
     * 返回 null 表示该 msgKey 已存在（重复请求），无需再发。
     */
    @Transactional(rollbackFor = Exception.class)
    public PendingSend enqueueTimeout(long errandId, int round, long version) {
        String msgKey = TimeoutPolicy.msgKey(errandId, round);
        String payload = TimeoutPolicy.payload(errandId, round, version);
        Instant deliverAt = errandRepository.findById(errandId)
                .map(Errand::confirmDeadlineAt)
                .orElseThrow(() -> new IllegalStateException("抢中后未写入确认截止时间 errandId=" + errandId));

        // 幂等第三件：msg_key 唯一索引，同一轮只登记一次
        boolean fresh = localMessageRepository.enqueue(
                idGenerator.nextId(), msgKey, TimeoutPolicy.TOPIC_CONFIRM_TIMEOUT, payload, deliverAt);
        return fresh ? new PendingSend(TimeoutPolicy.TOPIC_CONFIRM_TIMEOUT, msgKey, payload, deliverAt) : null;
    }
}
