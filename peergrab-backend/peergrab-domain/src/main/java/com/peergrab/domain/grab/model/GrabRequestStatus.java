package com.peergrab.domain.grab.model;

/**
 * 抢单请求的持久状态。
 *
 * <p>PROCESSING 表示请求已登记但结果未确定；一旦进入终态就不允许回到 PROCESSING，
 * 这样客户端用同一个 requestId 重放时永远拿到同一结果，不会因为重试而二次占名额。</p>
 */
public enum GrabRequestStatus {
    PROCESSING,
    SUCCEEDED,
    REJECTED,
    EXPIRED;

    public boolean terminal() {
        return this != PROCESSING;
    }
}
