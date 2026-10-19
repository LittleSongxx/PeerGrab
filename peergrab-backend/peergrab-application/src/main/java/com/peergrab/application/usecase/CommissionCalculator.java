package com.peergrab.application.usecase;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 佣金计算的唯一实现：结算、自动结算批量、仲裁分账必须调用同一段代码。
 *
 * <p>用十进制 BigDecimal 而不是 {@code total * rate} 再 {@code Math.floor}：
 * 0.29 这类费率在二进制下是 0.289999...，直接乘会在 100 分场景少取 1 分。
 *
 * <p>费率必须在构造期校验：若误配成 5（本意 5%），佣金会大于托管金额，
 * 结算会抛异常并让自动结算无限重试，属于启动期就该失败的错误。
 */
public final class CommissionCalculator {

    private CommissionCalculator() {
    }

    public static double requireValidRate(double rate) {
        if (Double.isNaN(rate) || rate < 0 || rate >= 1) {
            throw new IllegalArgumentException("佣金费率必须在 [0,1) 之间: " + rate);
        }
        return rate;
    }

    public static long commissionCents(long totalCents, double rate) {
        return BigDecimal.valueOf(totalCents)
                .multiply(BigDecimal.valueOf(requireValidRate(rate)))
                .setScale(0, RoundingMode.DOWN)
                .longValueExact();
    }
}
