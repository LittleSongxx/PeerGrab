package com.peergrab.domain.grab.ports;

/**
 * 可信跑腿身份的共享准入预算。
 *
 * <p>不是一个“限流中间件”的替代品，而是抢单链路的第二道闸：
 * <ul>
 *   <li>跨任务桶：一个跑腿在全局范围内每秒能发起多少次抢单尝试；</li>
 *   <li>单任务桶：同一跑腿对同一任务的重试不能无限刷；</li>
 *   <li>查询预算：202/网络失败后的结果查询也必须有界，否则可被用来放大 Redis/DB 压力。</li>
 * </ul>
 * 每次 HTTP 重试仍须计费（包括同 requestId 的重放），因此限流放在持久请求登记之前。
 * Redis 不可用时返回 UNAVAILABLE，由入口按 503 处理——不能把“限流不可用”当成“额度充足”。</p>
 */
public interface RunnerRateLimiterPort {

    Decision checkGrab(long runnerId, long errandId);

    Decision checkQuery(long runnerId);

    enum Status { ALLOWED, LIMITED, UNAVAILABLE }

    record Decision(Status status, long retryAfterMillis) {
        public static Decision allowed() {
            return new Decision(Status.ALLOWED, 0);
        }

        public static Decision limited(long millis) {
            return new Decision(Status.LIMITED, Math.max(1, millis));
        }

        public static Decision unavailable() {
            return new Decision(Status.UNAVAILABLE, 1000);
        }
    }
}
