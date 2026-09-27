package com.peergrab.application.usecase;

import com.peergrab.domain.credit.ports.CreditRepository;
import com.peergrab.domain.errand.model.Errand;
import com.peergrab.domain.errand.model.ErrandStatus;
import com.peergrab.domain.errand.ports.DelayMessagePort;
import com.peergrab.domain.errand.ports.ErrandRepository;
import com.peergrab.domain.errand.ports.ErrandQueryPort;
import com.peergrab.domain.errand.ports.LocalMessageRepository;
import com.peergrab.domain.grab.ports.CandidateQueuePort;
import com.peergrab.domain.grab.ports.GrabSlotPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 确认超时自动流转：抢中者限时未确认，任务转给候选队列下一位；候选空则回退重新开放。
 *
 * 幂等三件套（架构文档 7.4）：
 *   1. version CAS —— 任务被任何操作改动过，旧消息的 CAS 必然失败
 *   2. round 轮次校验 —— 区分"消息是第几轮发出的"，防 A→B→A 轮回时旧消息误判
 *   3. local_message.msg_key 唯一索引 —— 同一轮的消息只登记一次
 *
 * 消息一定会重复投递（MQ 语义是 at-least-once），所以每个动作都必须可重复执行。
 *
 * 本类刻意不加 @Transactional：Redis 操作不受数据库事务保护，
 * 把它们圈进事务只会拉长事务、放大锁竞争。事务边界收在 TimeoutTransferStep 里。
 */
@Service
public class TimeoutTransferUseCase {

    private static final Logger log = LoggerFactory.getLogger(TimeoutTransferUseCase.class);

    private final ErrandRepository errandRepository;
    private final CandidateQueuePort candidateQueue;
    private final GrabSlotPort grabSlotPort;
    private final DelayMessagePort delayMessagePort;
    private final LocalMessageRepository localMessageRepository;
    private final TimeoutTransferStep step;
    private final CacheEvictSupport cacheEvict;
    private final CreditRepository creditRepository;
    private final ErrandQueryPort errandQueryPort;
    private final int minCreditScore;
    private final int maxOngoing;
    /**
     * 最大流转轮次。
     *
     * 没有这个上限会出问题：一个爆款任务如果有 1999 个候选人，就会流转 1999 轮，
     * 每轮 5 分钟等于 7 天都结束不了，worker 一直在为它做无用功。
     * 实测中确实出现过 round 涨到 20+ 仍在继续的情况。
     * 达到上限后直接回退 PUBLISHED 让所有人重新抢，比继续挨个试更合理。
     */
    private final int maxTransferRounds;

    public TimeoutTransferUseCase(ErrandRepository errandRepository,
                                  CandidateQueuePort candidateQueue,
                                  GrabSlotPort grabSlotPort,
                                  DelayMessagePort delayMessagePort,
                                  LocalMessageRepository localMessageRepository,
                                  TimeoutTransferStep step,
                                  CacheEvictSupport cacheEvict,
                                  CreditRepository creditRepository,
                                  ErrandQueryPort errandQueryPort,
                                  @Value("${peergrab.credit.min-score:40}") int minCreditScore,
                                  @Value("${peergrab.credit.max-ongoing:5}") int maxOngoing,
                                  @Value("${peergrab.timeout.max-transfer-rounds:5}") int maxTransferRounds) {
        this.errandRepository = errandRepository;
        this.candidateQueue = candidateQueue;
        this.grabSlotPort = grabSlotPort;
        this.delayMessagePort = delayMessagePort;
        this.localMessageRepository = localMessageRepository;
        this.step = step;
        this.cacheEvict = cacheEvict;
        this.creditRepository = creditRepository;
        this.errandQueryPort = errandQueryPort;
        this.minCreditScore = minCreditScore;
        this.maxOngoing = maxOngoing;
        this.maxTransferRounds = maxTransferRounds;
    }

    public enum Outcome {
        /** 已流转给下一位候选人 */
        TRANSFERRED,
        /** 候选队列空，任务回退重新开放 */
        REVERTED,
        /** 消息已失效：任务已确认、已被处理或轮次不匹配（幂等生效） */
        SKIPPED
    }

    /**
     * 处理一条超时消息。
     *
     * @param expectedRound 消息携带的轮次，与当前 round 不符即判定为旧消息
     */
    public Outcome handleTimeout(long errandId, int expectedRound) {
        Errand errand = errandRepository.findById(errandId).orElse(null);
        if (errand == null) {
            return Outcome.SKIPPED;
        }
        // 幂等第二件：状态或轮次不匹配，说明任务已被确认/已流转过，这条是旧消息
        if (errand.status() != ErrandStatus.LOCKED || errand.round() != expectedRound) {
            log.debug("超时消息已失效 errandId={} status={} round={} expectedRound={}",
                    errandId, errand.status(), errand.round(), expectedRound);
            return Outcome.SKIPPED;
        }

        // 消息只负责唤醒。先用数据库时钟检查，避免早到消息提前领取候选；
        // 后续 transfer/revert 的条件 UPDATE 还会在同一事务中复核。
        if (!errandRepository.confirmTimeoutDue(errandId, expectedRound)) {
            return Outcome.SKIPPED;
        }

        // 超过最大流转轮次：不再逐个试候选人，直接回退让所有人重新抢
        if (errand.round() >= maxTransferRounds) {
            log.info("流转轮次已达上限 errandId={} round={} max={}，回退重新开放",
                    errandId, errand.round(), maxTransferRounds);
            return revertAndReturnSlot(errand);
        }

        Optional<CandidateQueuePort.Candidate> next;
        while ((next = candidateQueue.pollBest(errandId)).isPresent()) {
            var candidate = next.get();
            long nextRunner = candidate.runnerId();
            // 入队时的资格只是快照；轮到候选人时须再次验证。
            boolean eligible;
            try {
                eligible = nextRunner != errand.publisherId()
                        && (errand.grabberId() == null || nextRunner != errand.grabberId())
                        && creditRepository.scoreOf(nextRunner) >= minCreditScore
                        && errandQueryPort.countOngoingByRunner(nextRunner) < maxOngoing;
            } catch (RuntimeException e) {
                // 释放当前租约；即使 Redis 也失败，租约到期仍会重新入队。
                releaseCandidate(errandId, candidate);
                throw e;
            }
            if (!eligible) {
                log.info("候选人已不符合流转资格 errandId={} runnerId={}", errandId, nextRunner);
                candidateQueue.acknowledge(errandId, candidate);
                continue;
            }
            TimeoutTransferStep.StepResult result;
            try {
                result = step.transfer(errand, nextRunner);
            } catch (RuntimeException e) {
                releaseCandidate(errandId, candidate);
                throw e;
            }
            if (!result.applied()) {
                if (result.quotaFull()) {
                    // 预检查后又抢中别的任务；事务内额度裁决为准，继续找下一位。
                    log.info("候选人额度已满 errandId={} runnerId={}", errandId, nextRunner);
                    candidateQueue.acknowledge(errandId, candidate);
                    continue;
                }
                // CAS 失败：任务刚被确认或被别的消费者处理了。
                // 释放租约，按原 score 保留排队顺序。
                releaseCandidate(errandId, candidate);
                return Outcome.SKIPPED;
            }
            // DB 已提交；Redis 确认失败时租约最终会恢复，再次流转仍受状态 CAS 保护。
            try {
                candidateQueue.acknowledge(errandId, candidate);
            } catch (RuntimeException e) {
                log.warn("任务已流转但候选租约确认失败，待到期恢复 errandId={} runnerId={}",
                        errandId, nextRunner, e);
            }
            cacheEvict.evictAfterCommit(errandId);
            dispatch(result.pendingSend());
            log.info("任务已流转 errandId={} round={} -> nextRunner={}",
                    errandId, expectedRound + 1, nextRunner);
            return Outcome.TRANSFERRED;
        }

        // 别的 worker 正持有候选租约时不可回退；扫描任务会在租约到期后重试。
        if (candidateQueue.size(errandId) > 0) {
            return Outcome.SKIPPED;
        }
        log.info("候选队列为空，任务回退重新开放 errandId={}", errandId);
        return revertAndReturnSlot(errand);
    }

    private void releaseCandidate(long errandId, CandidateQueuePort.Candidate candidate) {
        try {
            candidateQueue.release(errandId, candidate);
        } catch (RuntimeException e) {
            log.warn("候选租约释放失败，待到期恢复 errandId={} runnerId={}",
                    errandId, candidate.runnerId(), e);
        }
    }

    /** 回退到 PUBLISHED 后丢弃上一轮 Redis 快照，下次抢单由数据库 CAS 裁决。 */
    private Outcome revertAndReturnSlot(Errand errand) {
        var result = step.revert(errand);
        if (!result.applied()) {
            return Outcome.SKIPPED;
        }
        // Redis grabbed 集合可能还保存首轮持有人，不能按当前 grabber 做 SREM/INCR。
        // 失效失败也不影响 DB 真值：抢单时若 Redis 显示已满但 DB 开放会走 CAS 兜底。
        try {
            grabSlotPort.invalidate(errand.id());
        } catch (RuntimeException e) {
            log.warn("回退后 Redis 名额失效失败，后续由数据库 CAS 兜底 errandId={}", errand.id(), e);
        }
        try {
            candidateQueue.clear(errand.id());
        } catch (RuntimeException e) {
            log.warn("回退后候选队列清理失败 errandId={}", errand.id(), e);
        }
        // 回退到 PUBLISHED、名额归还，详情缓存必须失效
        try {
            cacheEvict.evictAfterCommit(errand.id());
        } catch (RuntimeException e) {
            log.warn("任务已回退，详情缓存失效失败 errandId={}", errand.id(), e);
        }
        return Outcome.REVERTED;
    }

    /** 抢单成功后登记首轮超时消息，供 GrabErrandUseCase 在事务提交后调用 */
    public void scheduleFirstTimeout(long errandId, int round, long version) {
        dispatch(step.enqueueTimeout(errandId, round, version));
    }

    /**
     * 事务提交后再发 MQ。
     * 发送失败不抛出：消息已在 local_message 里是 PENDING 状态，
     * worker 的重发 job 会兜底，业务不受影响。
     */
    private void dispatch(TimeoutTransferStep.PendingSend send) {
        if (send == null) {
            return;
        }
        if (!delayMessagePort.available()) {
            // The DB scanner owns due work until MQ is enabled again; keep the
            // local_message row PENDING so the retry worker can later publish it.
            return;
        }
        try {
            delayMessagePort.send(send.topic(), send.msgKey(), send.payload(), send.deliverAt());
            localMessageRepository.markSent(send.msgKey());
        } catch (RuntimeException e) {
            log.warn("超时消息发送失败，留待 worker 重发 msgKey={}", send.msgKey(), e);
        }
    }

}
