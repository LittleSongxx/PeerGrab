package com.peergrab.domain.wallet.model;

import com.peergrab.shared.Money;

/**
 * 资金流水（事实来源）。每笔资金动作借方总额必须等于贷方总额；
 * 结算可以是一借多贷。accountVersion 对应本笔写入后的账户版本，
 * 用于按账户严格排序和审计；旧版流水的该列允许为空。
 *
 * bizNo 上有唯一索引做幂等：重复请求会撞唯一索引，应用层捕获后返回"已处理"，
 * 比"先查再插"可靠，因为唯一索引的判定与插入是数据库层的一个原子动作。
 */
public record LedgerEntry(long id, String bizNo, long accountId, long userId, Direction direction,
                          Money amount, Money balanceAfter, long accountVersion,
                          RefType refType, long refId) {

    public enum Direction {
        /** 借：资金流出该账户 */
        DEBIT,
        /** 贷：资金流入该账户 */
        CREDIT
    }

    public enum RefType {
        ESCROW, SETTLE, REFUND, RECHARGE, WITHDRAW
    }

    /** 托管场景的幂等键：一个任务只能托管一次 */
    public static String escrowBizNo(long errandId) {
        return "escrow:" + errandId;
    }

    /** 结算幂等键：一个任务只能结算一次。并发重复结算靠这个键撞唯一索引挡住 */
    public static String settleBizNo(long errandId) {
        return "settle:" + errandId;
    }

    /** 退款幂等键：一个任务只能退款一次 */
    public static String refundBizNo(long errandId) {
        return "refund:" + errandId;
    }
}
