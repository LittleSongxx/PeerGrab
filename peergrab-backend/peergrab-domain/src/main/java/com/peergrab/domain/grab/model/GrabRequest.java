package com.peergrab.domain.grab.model;

import java.time.Instant;

/**
 * 抢单请求的持久事实。
 *
 * <p>它是抢单幂等的唯一真相：requestId 相同、payload 相同就返回同一结果；
 * Redis 只承担"挡流量/快速占位"，崩溃后以这里和数据库业务事实为准。
 * leaseUntil 由数据库时钟签发，不因客户端重试而续期，避免旧请求在执行队列里复活。</p>
 */
public record GrabRequest(long id, long campusId, long errandId, long runnerId,
        String requestId, String payloadHash, int requestRound, long baseErrandVersion,
        GrabRequestStatus status, String reservationToken, Instant leaseUntil,
        String resultCode, int httpStatus, String resultJson,
        Instant createdAt, Instant completedAt) {

    public static final String OPERATION = "GRAB";

    /** 只有仍处于处理态、令牌匹配且租约未过期时，执行线程才能提交业务写入。 */
    public boolean executable(String token, Instant databaseNow) {
        return status == GrabRequestStatus.PROCESSING
                && reservationToken.equals(token)
                && leaseUntil.isAfter(databaseNow);
    }

    /** 登记新请求时使用的参数快照。payloadHash 用来拒绝"同一 requestId 换了请求内容"的重放。 */
    public record NewRequest(long id, long campusId, long errandId, long runnerId,
            String requestId, String payloadHash, int requestRound, long baseErrandVersion,
            String reservationToken, int leaseSeconds) {
    }
}
