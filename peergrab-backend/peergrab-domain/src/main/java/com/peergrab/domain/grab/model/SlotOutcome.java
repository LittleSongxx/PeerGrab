package com.peergrab.domain.grab.model;

/**
 * Redis Lua 原子判定的返回码。
 * 与 grab.lua 的返回值严格一对一，改脚本必须同步改这里。
 */
public enum SlotOutcome {

    /** 占位成功，可以进入 DB 落库 */
    ACQUIRED(1),
    /** 名额已满，进候选队列 */
    SLOT_FULL(0),
    /** 同一用户重复抢（INV-2） */
    ALREADY_GRABBED(-1),
    /** 同一跑腿重复提交同一 requestId；最终结果仍须以数据库记录为准 */
    DUPLICATE_REQUEST(-2),
    /** 任务不可抢（不存在或状态不对） */
    NOT_GRABBABLE(-3),
    /** Redis 名额键过期或丢失；由数据库 CAS 兜底 */
    SLOT_MISSING(-4),
    /** 同一 requestId 已被另一位跑腿占用 */
    REQUEST_ID_CONFLICT(-5);

    private final long code;

    SlotOutcome(long code) {
        this.code = code;
    }

    public long code() {
        return code;
    }

    public static SlotOutcome fromCode(long code) {
        for (SlotOutcome o : values()) {
            if (o.code == code) {
                return o;
            }
        }
        throw new IllegalArgumentException("未知的 Lua 返回码: " + code);
    }
}
