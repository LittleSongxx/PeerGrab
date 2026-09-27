package com.peergrab.domain.wallet.ports;

/**
 * 资金事件发布端口。
 *
 * 调用方在资金本地事务中登记待发送事件；后台 worker 再投递事件。
 * MQ 故障只会延后通知，不阻断结算、退款或仲裁。
 */
public interface FundEventPort {

    /** 在调用方已开启的资金事务中登记待发送事件。 */
    void append(FundEvent event);

    /** 资金事件。bizNo 同时是 outbox 主键和 MQ 消息 key。 */
    record FundEvent(String bizNo, String type, long errandId, long publisherId,
                     long runnerId, long amountCents, long commissionCents) {}
}
