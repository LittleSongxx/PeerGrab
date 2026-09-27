package com.peergrab.application.usecase;

import com.peergrab.shared.MessagePayloadCodec;

/**
 * 延迟任务的 topic 与幂等键约定。
 *
 * P2 只有一种延迟任务（确认超时），P3 增加"送达后 24h 自动结算"，所以从
 * TimeoutPolicy 泛化成这里——两类延迟任务的 msg_key 前缀必须区分开，
 * 否则 local_message 的唯一索引会让它们互相顶掉。
 *
 * 定时消息与业务事务通过同库 local_message 连接：业务提交后 Worker 投递，
 * MQ 提前/重复唤醒都交由数据库状态和截止时间裁决。
 */
public final class DelayTaskPolicy {

    /** 确认超时流转（DELAY 类型 topic） */
    public static final String TOPIC_CONFIRM_TIMEOUT = "errand-confirm-timeout";
    /** 送达后自动结算（DELAY 类型 topic） */
    public static final String TOPIC_AUTO_SETTLE = "errand-auto-settle";

    private DelayTaskPolicy() {
    }

    /** 超时流转幂等键：带 round 才能识别旧轮次消息 */
    public static String timeoutKey(long errandId, int round) {
        return "timeout:" + errandId + ":" + round;
    }

    /** 自动结算幂等键：一个任务只会送达一次，不需要轮次 */
    public static String autoSettleKey(long errandId) {
        return "autosettle:" + errandId;
    }

    /** 消息体只带定位与幂等判定所需字段，不塞业务快照（会过期） */
    public static String payload(long errandId, int round, long version) {
        return MessagePayloadCodec.timeout(errandId, round, version);
    }

    /**
     * 自动结算消息体：与超时流转的 payload 字段不同（没有 round/version），
     * 所以单独定义——两类消息的消费者各自解析自己的格式，
     * 不能共用一个 payload 方法（P4 实测：共用导致消费者解析出空串抛异常）
     */
    public static String autoSettlePayload(long errandId) {
        return MessagePayloadCodec.autoSettle(errandId);
    }
}
