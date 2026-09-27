package com.peergrab.domain.errand.ports;

/**
 * 跑腿在途额度。调用方必须处于数据库事务中；锁持续到事务提交。
 * LOCKED、ACCEPTED、PICKED_UP 都占用额度，送达后自动释放。
 */
public interface RunnerQuotaPort {

    /**
     * 串行化同一跑腿的抢中与候选递补，然后按数据库真值检查额度。
     * 只有本方法返回 true 后，当前事务才可将任务分配给该跑腿。
     */
    boolean lockAndHasCapacity(long runnerId, int maxOngoing);
}
