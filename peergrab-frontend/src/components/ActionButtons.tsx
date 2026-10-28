import { useState } from "react";
import { api, ACTION_TEXT, BizError } from "../api";
import type { ErrandCard } from "../api";
import { grabErrand } from "../grabRequests";
import { showToast } from "../utils/toast";

/** settle/cancel 的幂等成功结果：后端重复处理时 data.result 落在这两个值上 */
const ALREADY_TEXT: Record<string, string> = {
  ALREADY_SETTLED: "该任务此前已完成结算，无需重复处理。",
  ALREADY_REFUNDED: "该任务此前已完成退款，无需重复处理。",
};

/**
 * v2 抢单失败的固定提示：后端 code 是稳定契约，文案在前端集中维护；
 * 未覆盖的 code 用后端 message 兜底。429/503 是瞬时状态（保留请求键，稍后重试同一 ID），
 * 其余 4xx 是确定答案（清理请求键，用户换新 ID 重新发起）。
 */
const GRAB_FAILURE_HINT: Record<string, string> = {
  SLOT_FULL: "名额已满，任务已被其他跑腿抢中",
  ALREADY_GRABBED: "你已抢过该任务，请到任务详情确认接单",
  STALE_VERSION: "任务状态已被更新，请刷新后重试",
  REQUEST_CONFLICT: "本次请求与已有请求冲突，请重新发起",
  REQUEST_EXPIRED: "本次抢单请求已过期，请重新发起",
  REQUEST_NOT_FOUND: "抢单请求已失效，请重新发起",
  GRAB_RATE_LIMITED: "当前抢单请求过多，请稍后再试",
  DEPENDENCY_UNAVAILABLE: "服务繁忙，请稍后重试",
  ERRAND_NOT_FOUND: "任务不存在或已被删除",
  ERRAND_NOT_GRABBABLE: "任务当前不可抢",
  SELF_GRAB_FORBIDDEN: "不能抢自己发布的任务",
  ROLE_NOT_ALLOWED: "当前身份不能参与抢单",
  CREDIT_TOO_LOW: "信用分不足，暂时不能抢单",
  INVALID_ARGUMENT: "请求参数无效，请刷新后重试",
};

interface Props {
  card: ErrandCard;
  onChanged: () => void;
  compact?: boolean;
  onRequestArbitration?: () => void;
}

/**
 * 操作按钮完全由后端 availableActions 驱动。
 * 前端没有第二份状态机——这里没有任何 "status === 'xxx' && isPublisher" 的判断，
 * 按钮存在与否由 ErrandActionResolver（后端）决定，两边不可能漂移。
 */
export default function ActionButtons({ card, onChanged, compact, onRequestArbitration }: Props) {
  const [busy, setBusy] = useState<string | null>(null);

  const run = async (action: string) => {
    if (action === "ARBITRATE") {
      if (onRequestArbitration) onRequestArbitration();
      else location.hash = `#/detail/${card.id}`;
      return;
    }
    setBusy(action);
    try {
      if (action === "GRAB") {
        // v2 客户端负责：请求键持久化 → POST → 202/网络失败用同一键查询恢复。
        const outcome = await grabErrand(card.id, card.round);
        if (outcome.kind === "success") {
          showToast(
            "success",
            outcome.replayed ? "抢单成功（此前提交的请求已生效）。" : "抢单成功，记得及时确认接单。",
          );
        } else if (outcome.kind === "processing") {
          showToast("info", outcome.message);
        } else {
          const hint = GRAB_FAILURE_HINT[outcome.code] ?? outcome.message;
          showToast(outcome.retryable ? "info" : "error", `${hint}（${outcome.code}）`);
        }
      } else {
        const result = await api.action(card.id, action.toLowerCase() as never);
        if (action === "SETTLE" || action === "CANCEL") {
          window.dispatchEvent(new Event("peergrab:wallet-changed"));
        }
        const already = result?.result ? ALREADY_TEXT[result.result] : undefined;
        showToast(already ? "info" : "success", already ?? `${ACTION_TEXT[action]}成功`);
      }
      onChanged();
    } catch (error) {
      if (error instanceof BizError) {
        showToast("error", `${error.message}（${error.code}）`);
      } else {
        showToast("error", "网络错误，请稍后重试。");
      }
    } finally {
      setBusy(null);
    }
  };

  if (card.availableActions.length === 0) return null;

  return (
    <div className={compact ? "actions compact" : "actions"}>
      {card.availableActions.map((action) => (
        <button
          key={action}
          type="button"
          disabled={busy !== null}
          onClick={() => void run(action)}
          aria-busy={busy === action}
          data-testid={`action-${action.toLowerCase()}`}
          className={`btn action-button btn-${action.toLowerCase()} ${busy === action ? "busy" : ""}`}
        >
          {busy === action ? "处理中…" : (ACTION_TEXT[action] ?? action)}
        </button>
      ))}
    </div>
  );
}
