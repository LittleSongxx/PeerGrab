package com.peergrab.application.usecase;

import com.peergrab.domain.errand.model.Errand;
import com.peergrab.domain.errand.ports.ErrandRepository;
import com.peergrab.domain.notify.ports.RealtimeNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 抢单提交后的副作用集合：首轮超时消息、详情缓存失效、实时通知。
 *
 * <p>正常请求由 {@link GrabErrandUseCase} 在提交后调用；如果进程在"数据库已提交、
 * 响应未返回"之间崩溃，恢复线程会补跑这里，保证任务不会因为缺一条超时消息而永久卡住。
 * 每个动作独立 try/catch：任何副作用失败都不能把已提交的业务事实改写成失败。</p>
 */
@Service
public class GrabCommitSideEffects {

    private static final Logger log = LoggerFactory.getLogger(GrabCommitSideEffects.class);

    private final ErrandRepository errands;
    private final TimeoutTransferUseCase timeout;
    private final CacheEvictSupport cache;
    private final RealtimeNotifier notifier;

    public GrabCommitSideEffects(ErrandRepository errands,
                                 TimeoutTransferUseCase timeout,
                                 CacheEvictSupport cache,
                                 RealtimeNotifier notifier) {
        this.errands = errands;
        this.timeout = timeout;
        this.cache = cache;
        this.notifier = notifier;
    }

    public void run(long errandId) {
        Errand current = errands.findById(errandId).orElse(null);
        if (current == null) {
            return;
        }
        try {
            timeout.scheduleFirstTimeout(errandId, current.round(), current.version());
        } catch (RuntimeException e) {
            // Worker 的超时扫描仍可兜底；已提交的抢单事实不容因消息发送失败而改写。
            log.warn("抢单已提交，首轮超时消息登记失败 errandId={}", errandId, e);
        }
        try {
            cache.evictAfterCommit(errandId);
        } catch (RuntimeException e) {
            log.warn("抢单已提交，详情缓存失效失败 errandId={}", errandId, e);
        }
        try {
            notifier.errandStatusChanged(current.id(), current.publisherId(), current.grabberId(),
                    current.status().name(), current.round());
        } catch (RuntimeException e) {
            log.warn("抢单已提交，实时通知失败 errandId={}", errandId, e);
        }
    }
}
