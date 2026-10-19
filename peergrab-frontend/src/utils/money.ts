/**
 * 金额格式化与解析的唯一入口（金额一律以整数"分"在前后端之间传递）。
 *
 * 为什么不用 Number 直接乘 100：`1.005 * 100` 在 IEEE-754 下会得到 100.4999...，
 * Math.round 后再除回去就可能出现 1 分误差。这里按十进制字符串拆分，
 * 超过两位小数直接拒绝而不是静默四舍五入。
 */

/** 单个悬赏金额上限（100 万元），仍远小于 JS 安全整数。 */
export const MAX_REWARD_CENTS = 100_000_000;

/** 后端把 long（含金额）统一序列化为字符串，金额字段按 number | string 收口。 */
export type Cents = number | string;

export interface ParsedYuan {
  /** 解析出的分；解析失败时为 0。 */
  cents: number;
  /** 失败原因；成功时为 undefined。 */
  error?: string;
}

/** 分 -> 展示字符串，例如 150 => ¥1.50 */
export function formatCents(cents: Cents): string {
  const value = typeof cents === "number" ? cents : Number(cents);
  if (!Number.isFinite(value)) return "—";
  return `¥${(value / 100).toFixed(2)}`;
}

/** "15" / "15.5" / "0.29" -> 整数分；非法输入返回可展示的错误。 */
export function parseYuanToCents(input: string): ParsedYuan {
  const text = input.trim();
  if (!text) return { cents: 0, error: "请输入悬赏金额" };
  if (/^\d+\.\d{3,}$/.test(text)) {
    return { cents: 0, error: "悬赏金额最多保留两位小数" };
  }
  if (!/^\d+(\.\d{1,2})?$/.test(text)) {
    return { cents: 0, error: "请输入正确的金额，例如 15 或 15.50" };
  }
  const [yuanPart, fractionPart = ""] = text.split(".");
  const cents = Number(yuanPart) * 100 + Number(fractionPart.padEnd(2, "0"));
  if (!Number.isSafeInteger(cents)) {
    return { cents: 0, error: "悬赏金额过大" };
  }
  if (cents <= 0) {
    return { cents: 0, error: "悬赏金额必须大于 0" };
  }
  if (cents > MAX_REWARD_CENTS) {
    return { cents: 0, error: "单笔悬赏不能超过 100 万元" };
  }
  return { cents };
}
