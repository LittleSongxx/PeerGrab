package com.peergrab.domain.grab.ports;

import java.util.Optional;

/**
 * 候选队列端口：抢单失败的人在这里排队，等抢中者确认超时后自动流转。
 * 用 Redis ZSET 实现，score = 抢单时间戳 - 信用分加权。
 *
 * 为什么用 ZSET 而不是 List：需要按分数取最优候选人，还要支持"用户主动退出候选"
 * 这种 O(log N) 的定位删除，List 做不到。
 */
public interface CandidateQueuePort {

    record Candidate(long runnerId, double score, String claimId) {
        public Candidate(long runnerId, double score) {
            this(runnerId, score, null);
        }
    }

    /** 原子入队，并返回此时可用候选人与在途租约的总数。 */
    long offer(long errandId, long runnerId, double score);

    /** 租约领取当前最优候选人；进程崩溃后租约到期会重新入队。 */
    Optional<Candidate> pollBest(long errandId);

    /** DB 流转提交或候选失效后确认移除，仅当前租约持有人可确认。 */
    void acknowledge(long errandId, Candidate candidate);

    /** DB 流转失败时释放租约并保留原始分数。 */
    void release(long errandId, Candidate candidate);

    long size(long errandId);

    /** 回退重新开放时清理上一轮的候选意向。 */
    void clear(long errandId);
}
