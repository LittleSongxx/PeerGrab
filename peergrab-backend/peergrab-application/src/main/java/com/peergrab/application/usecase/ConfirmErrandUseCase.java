package com.peergrab.application.usecase;

import com.peergrab.domain.errand.model.Errand;
import com.peergrab.domain.notify.ports.RealtimeNotifier;
import com.peergrab.domain.errand.model.ErrandStatus;
import com.peergrab.domain.errand.ports.ErrandRepository;
import com.peergrab.domain.grab.ports.CandidateQueuePort;
import com.peergrab.shared.BizException;
import com.peergrab.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 跑腿确认接单：LOCKED -> ACCEPTED。
 *
 * 这个用例存在的意义是给超时流转提供对照分支——
 * 确认成功后 version 递增，那一轮的超时消息到达时 CAS 必然失败，
 * 于是"已确认的任务不会被误流转"是靠数据库保证的，不靠时间判断。
 */
@Service
public class ConfirmErrandUseCase {

    private static final Logger log = LoggerFactory.getLogger(ConfirmErrandUseCase.class);

    private final CacheEvictSupport cacheEvict;
    private final ErrandRepository errandRepository;
    private final CandidateQueuePort candidateQueue;
    private final RealtimeNotifier notifier;

    public ConfirmErrandUseCase(ErrandRepository errandRepository,
                                CacheEvictSupport cacheEvict,
                                RealtimeNotifier notifier,
                                CandidateQueuePort candidateQueue) {
        this.errandRepository = errandRepository;
        this.cacheEvict = cacheEvict;
        this.notifier = notifier;
        this.candidateQueue = candidateQueue;
    }

    public record Command(long errandId, long runnerId) {}

    @Transactional(rollbackFor = Exception.class)
    public void confirm(Command cmd) {
        Errand errand = errandRepository.findById(cmd.errandId())
                .orElseThrow(() -> new BizException(ErrorCode.ERRAND_NOT_FOUND, "id=" + cmd.errandId()));

        if (errand.status() == ErrandStatus.ACCEPTED) {
            // 重复确认按幂等处理，不报错
            return;
        }

        // 领域层先校验"是不是当前抢中者"，尽早失败并给出明确错误
        errand.acceptByRunner(cmd.runnerId(), errand.version());

        int affected = errandRepository.casAccept(cmd.errandId(), cmd.runnerId(), errand.version() - 1);
        if (affected == 0) {
            // CAS 失败说明这一瞬间任务被流转走了（worker 抢先一步）
            throw new BizException(ErrorCode.STALE_VERSION,
                    "确认失败，任务可能已被流转 errandId=" + cmd.errandId());
        }
        errandRepository.appendStatusLog(cmd.errandId(), ErrandStatus.LOCKED, ErrandStatus.ACCEPTED,
                errand.round(), cmd.runnerId());
        notifier.errandStatusChanged(errand.id(), errand.publisherId(), errand.grabberId(),
                ErrandStatus.ACCEPTED.name(), errand.round());

        // 状态变了，详情缓存必须失效。挂在事务提交后执行（见 CacheEvictSupport 的注释）
        cacheEvict.evictAfterCommit(cmd.errandId());
        clearCandidatesAfterCommit(cmd.errandId());
    }

    private void clearCandidatesAfterCommit(long errandId) {
        Runnable clear = () -> {
            try {
                candidateQueue.clear(errandId);
            } catch (RuntimeException e) {
                // 确认已提交；Redis 清理失败只影响临时排队数据，TTL 最终会清理。
                log.warn("任务确认后候选队列清理失败 errandId={}", errandId, e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    clear.run();
                }
            });
        } else {
            clear.run();
        }
    }
}
