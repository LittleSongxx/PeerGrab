package com.peergrab.worker;

import java.util.List;
import java.util.Map;

/** 扫描游标越过失败项前，必须先把它登记到持久重试队列。 */
public interface ScanRetryQueue {

    enum Type { CONFIRM_TIMEOUT, AUTO_SETTLE }

    record RetryItem(long errandId, int round, int attempts, String leaseToken) { }

    /** 已被队列接管的任务及其轮次；扫描器可安全越过这些任务。 */
    Map<Long, Integer> trackedRounds(Type type, List<Long> errandIds);

    void recordFailure(Type type, long errandId, int round, Exception failure);

    /** 每轮最多领取 limit 条；租约使多 Worker 不会同时处理同一重试项。 */
    List<RetryItem> claimDue(Type type, int limit);

    void complete(Type type, RetryItem item);

    void reschedule(Type type, RetryItem item, Exception failure);
}
