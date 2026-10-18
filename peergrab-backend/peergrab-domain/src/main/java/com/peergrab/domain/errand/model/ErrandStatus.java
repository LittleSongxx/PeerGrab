package com.peergrab.domain.errand.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * 任务状态机：用枚举 + 允许流转集合表达，而不是散落各处的 if-else。
 *
 * <p>注意 LOCKED -> LOCKED 是合法自环：代表"抢中者确认超时，流转给候选队列下一位"，
 * 靠 Errand.round 区分轮次；连续递补上限用 transfer_count 单独计数。</p>
 *
 * <p>本枚举只声明"状态允许去哪"，身份与业务前置条件仍由聚合方法校验；
 * 两者必须保持一致（例如已有人接单后不能由发单人单方面取消）。</p>
 */
public enum ErrandStatus {

    DRAFT,
    PUBLISHED,
    LOCKED,
    ACCEPTED,
    PICKED_UP,
    DELIVERED,
    SETTLED,
    CANCELLED,
    DISPUTED,
    REFUNDED;

    private static final Set<ErrandStatus> FROM_DRAFT = EnumSet.of(PUBLISHED, CANCELLED);
    private static final Set<ErrandStatus> FROM_PUBLISHED = EnumSet.of(LOCKED, CANCELLED);
    /** 已有人接单后不能由发单人单方面取消；LOCKED 可超时流转或回退重开。 */
    private static final Set<ErrandStatus> FROM_LOCKED = EnumSet.of(ACCEPTED, LOCKED, PUBLISHED);
    private static final Set<ErrandStatus> FROM_ACCEPTED = EnumSet.of(PICKED_UP, DISPUTED);
    private static final Set<ErrandStatus> FROM_PICKED_UP = EnumSet.of(DELIVERED, DISPUTED);
    private static final Set<ErrandStatus> FROM_DELIVERED = EnumSet.of(SETTLED, DISPUTED);
    private static final Set<ErrandStatus> FROM_DISPUTED = EnumSet.of(SETTLED, REFUNDED);
    private static final Set<ErrandStatus> TERMINAL = EnumSet.noneOf(ErrandStatus.class);

    public Set<ErrandStatus> allowedTargets() {
        return switch (this) {
            case DRAFT -> FROM_DRAFT;
            case PUBLISHED -> FROM_PUBLISHED;
            case LOCKED -> FROM_LOCKED;
            case ACCEPTED -> FROM_ACCEPTED;
            case PICKED_UP -> FROM_PICKED_UP;
            case DELIVERED -> FROM_DELIVERED;
            case DISPUTED -> FROM_DISPUTED;
            case SETTLED, REFUNDED, CANCELLED -> TERMINAL;
        };
    }

    public boolean canTransitTo(ErrandStatus target) {
        return allowedTargets().contains(target);
    }

    /** 只有 PUBLISHED 状态的任务能被抢 */
    public boolean grabbable() {
        return this == PUBLISHED;
    }
}
