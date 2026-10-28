package com.peergrab.application.usecase;

import com.peergrab.domain.errand.model.Errand;
import com.peergrab.domain.errand.model.ErrandStatus;
import com.peergrab.domain.errand.ports.ErrandRepository;
import com.peergrab.domain.grab.model.GrabRequest;
import com.peergrab.domain.grab.ports.GrabRequestRepository;
import com.peergrab.domain.grab.ports.GrabSlotPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 抢单请求的恢复用例：以数据库为入口收敛 PROCESSING 请求，不依赖扫 Redis key。
 *
 * <p>两类恢复：
 * <ol>
 *   <li>业务已提交但请求行未终结（进程在提交后、响应前崩溃）→ 收敛为 SUCCEEDED 并补跑副作用；</li>
 *   <li>业务未提交且租约已过期 → 收敛为 EXPIRED；若任务仍在 PUBLISHED 且无名额占用，
 *       丢弃可能残留的 Redis 预占快照，让下一次请求走数据库 CAS 兜底。</li>
 * </ol>
 * 绝不在"查不到 grab_record"时臆断成功，也不在没有数据库事实时直接恢复名额。</p>
 */
@Service
public class GrabRequestRecoveryUseCase {

    private static final Logger log = LoggerFactory.getLogger(GrabRequestRecoveryUseCase.class);

    private final GrabRequestRepository requests;
    private final GrabRequestStep step;
    private final GrabCommitSideEffects sideEffects;
    private final ErrandRepository errands;
    private final GrabSlotPort grabSlotPort;

    public GrabRequestRecoveryUseCase(GrabRequestRepository requests,
                                      GrabRequestStep step,
                                      GrabCommitSideEffects sideEffects,
                                      ErrandRepository errands,
                                      GrabSlotPort grabSlotPort) {
        this.requests = requests;
        this.step = step;
        this.sideEffects = sideEffects;
        this.errands = errands;
        this.grabSlotPort = grabSlotPort;
    }

    /** @return 本轮收敛的请求数（成功恢复 + 过期回收） */
    public int expireBatch(int limit) {
        int recovered = 0;
        int expired = 0;
        for (GrabRequest stale : requests.findExpired(limit)) {
            try {
                GrabRequestStep.ReconcileResult result = step.reconcile(stale);
                if (result.recoveredSuccess()) {
                    sideEffects.run(result.request().errandId());
                    recovered++;
                } else {
                    clearStaleRedisReservation(result.request());
                    expired++;
                }
            } catch (RuntimeException e) {
                // 收敛失败下一轮继续扫；请求行仍是 PROCESSING，不会丢。
                log.warn("抢单请求恢复失败 requestId={} errandId={}", stale.id(), stale.errandId(), e);
            }
        }
        if (recovered + expired > 0) {
            log.info("抢单请求恢复完成 恢复成功={} 过期回收={}", recovered, expired);
        }
        return recovered + expired;
    }

    /**
     * 只清"数据库明确说任务可抢、且没人占名额"时的陈旧 Redis 预占快照。
     * 若任务已被别人抢中/流转，交给线上链路正常处理，绝不盲目 INCR 名额。
     */
    private void clearStaleRedisReservation(GrabRequest request) {
        Errand errand = errands.findById(request.errandId()).orElse(null);
        if (errand == null || errand.status() != ErrandStatus.PUBLISHED
                || errand.grabberId() != null || errand.slotTaken() != 0) {
            return;
        }
        try {
            grabSlotPort.invalidate(request.errandId());
        } catch (RuntimeException e) {
            log.warn("陈旧 Redis 预占快照清理失败 errandId={}", request.errandId(), e);
        }
    }
}
