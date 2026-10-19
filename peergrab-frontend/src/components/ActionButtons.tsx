import { useRef, useState } from "react";
import { api, ACTION_TEXT, BizError, TRANSIENT_ERROR_CODES } from "../api";
import type { ErrandCard } from "../api";
import { newRequestId } from "../utils/requestId";
import { showToast } from "../utils/toast";

/** settle/cancel 的幂等成功结果：后端重复处理时 data.result 落在这两个值上 */
const ALREADY_TEXT: Record<string, string> = {
  ALREADY_SETTLED: "该任务此前已完成结算，无需重复处理。",
  ALREADY_REFUNDED: "该任务此前已完成退款，无需重复处理。",
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
  // 抢单幂等键：网络失败（非 BizError）重试时复用，收到业务响应后清理
  const pendingRequestId = useRef<Map<string, string>>(new Map());

  const run = async (action: string) => {
    if (action === "ARBITRATE") {
      if (onRequestArbitration) onRequestArbitration();
      else location.hash = `#/detail/${card.id}`;
      return;
    }
    setBusy(action);
    const grabbing = action === "GRAB";
    // 上一次是网络错误时复用同一个 requestId，服务端就不会把重试当成新请求
    const requestId = grabbing
      ? pendingRequestId.current.get(card.id) ?? newRequestId()
      : undefined;
    if (requestId) pendingRequestId.current.set(card.id, requestId);
    try {
      if (grabbing) {
        await api.grab(card.id, requestId);
        pendingRequestId.current.delete(card.id);
        showToast("success", "抢单成功，记得及时确认接单。");
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
        // 只有拿到确定的业务码才丢弃幂等键；网络/网关错误时服务端可能已经
        // 处理成功，重试必须复用同一个 requestId，否则会被误报 ALREADY_GRABBED。
        if (grabbing && !TRANSIENT_ERROR_CODES.has(error.code)) {
          pendingRequestId.current.delete(card.id);
        }
        showToast("error", `${error.message}（${error.code}）`);
      } else {
        // 网络错误：保留 requestId 供下一次重试复用
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
