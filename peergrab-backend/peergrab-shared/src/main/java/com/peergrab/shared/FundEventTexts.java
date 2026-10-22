package com.peergrab.shared;

/**
 * 资金事件对发单人/跑腿的文案。
 *
 * <p>同一条资金事件对两方的语义不同：发单人关心“托管总额及去向”，跑腿关心“自己净得多少”。
 * 文案抽到这里，落库消费者与实时推送消费者共用，避免两个进程写出不一致的通知。</p>
 *
 * <p>金额统一按“元”展示，避免“855 分”被误读成信用分/积分。</p>
 */
public final class FundEventTexts {

    private FundEventTexts() {
    }

    public static String publisherContent(String type, long runnerNetCents, long commissionCents) {
        long total = Math.addExact(runnerNetCents, commissionCents);
        String amount = Money.ofCents(total).toString();
        return switch (type) {
            case "SETTLED" -> String.format("任务已完成结算，托管 ¥%s 已分配给跑腿", amount);
            case "REFUNDED" -> String.format("任务已退款，¥%s 已退回你的账户", amount);
            case "ARBITRATED" -> String.format("争议已裁决，托管 ¥%s 已按裁决分配", amount);
            default -> "任务资金状态变动";
        };
    }

    public static String runnerContent(String type, long runnerNetCents) {
        return switch (type) {
            case "SETTLED", "ARBITRATED" ->
                    String.format("任务结算完成，你获得 ¥%s", Money.ofCents(runnerNetCents).toString());
            default -> publisherContent(type, runnerNetCents, 0);
        };
    }
}
