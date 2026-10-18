package com.peergrab.domain.errand.model;

import com.peergrab.shared.BizException;
import com.peergrab.shared.ErrorCode;

import java.util.Locale;

/**
 * 仲裁裁决方向。
 *
 * <p>把线上字符串解析收敛到领域枚举：仲裁会直接决定托管资金去向，
 * 绝不能用"非 PUBLISHER 即 RUNNER"的宽松解析——拼写错误、null 或空串
 * 都可能把资金判给错误的一方。</p>
 */
public enum ArbitrationFavor {

    /** 支持跑腿：托管资金按结算规则分配给跑腿与佣金账户 */
    RUNNER,
    /** 支持发单人：托管资金全额退回发单人 */
    PUBLISHER;

    /** 严格解析线上值；大小写与首尾空白容忍，其他一律拒绝。 */
    public static ArbitrationFavor fromWire(String value) {
        if (value == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "favor 不能为空");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "favor 不能为空");
        }
        return switch (normalized) {
            case "RUNNER" -> RUNNER;
            case "PUBLISHER" -> PUBLISHER;
            default -> throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "favor 只能是 RUNNER 或 PUBLISHER");
        };
    }
}
