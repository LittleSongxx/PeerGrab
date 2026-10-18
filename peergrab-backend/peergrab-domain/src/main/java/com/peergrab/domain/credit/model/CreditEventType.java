package com.peergrab.domain.credit.model;

/**
 * 信用事件类型与分值规则。
 *
 * <p>只有真正被业务链路触发的事件才保留在本枚举里。历史设计中的
 * CANCEL_AFTER_GRAB（本项目禁止抢单后单方取消）与 DELIVERY_LATE（没有配送时限模型）
 * 从未接线，已移除，避免文档/UI 展示不存在的规则。</p>
 *
 * <p>分值是业务规则不是环境参数：要调整就走代码评审，留下变更记录。
 * 每日 job 按最近 30 天事件重算，一次严重扣分会随窗口滑出，保留信用修复路径。</p>
 */
public enum CreditEventType {

    SETTLE(+2, "完成结算"),
    GRAB_TIMEOUT_REVERT(-5, "抢中后超时未确认"),
    DISPUTE_LOSE(-8, "争议败诉");

    private final int delta;
    private final String description;

    CreditEventType(int delta, String description) {
        this.delta = delta;
        this.description = description;
    }

    public int delta() {
        return delta;
    }

    public String description() {
        return description;
    }

    /** 滑动窗口：天 */
    public static final int WINDOW_DAYS = 30;
    /** 初始分 */
    public static final int BASE_SCORE = 60;
    /** 分数下限：防止负分无限叠加，也给用户留修复空间 */
    public static final int MIN_SCORE = 0;
    /** 分数上限 */
    public static final int MAX_SCORE = 100;
}
