import { useRef, useState } from 'react';
import { api, ACTION_TEXT, BizError } from '../api';
import type { ErrandCard } from '../api';

/** settle/cancel 的幂等成功结果：后端重复处理时 data.result 落在这两个值上 */
const ALREADY_TEXT: Record<string, string> = {
  ALREADY_SETTLED: '该任务此前已完成结算，无需重复处理。',
  ALREADY_REFUNDED: '该任务此前已完成退款，无需重复处理。',
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
  const [message, setMessage] = useState<{ kind: 'success' | 'error'; text: string } | null>(null);
  // 抢单幂等键：网络失败（非 BizError）重试时复用，收到业务响应后清理
  const pendingRequestId = useRef<Map<string, string>>(new Map());

  const run = async (action: string) => {
    if (action === 'ARBITRATE') {
      if (onRequestArbitration) onRequestArbitration();
      else location.hash = `#/detail/${card.id}`;
      return;
    }
    setBusy(action);
    setMessage(null);
    const grabbing = action === 'GRAB';
    // 上一次是网络错误时复用同一个 requestId，服务端就不会把重试当成新请求
    const requestId = grabbing
      ? pendingRequestId.current.get(card.id) ?? crypto.randomUUID()
      : undefined;
    if (requestId) pendingRequestId.current.set(card.id, requestId);
    try {
      if (grabbing) {
        await api.grab(card.id, requestId);
        pendingRequestId.current.delete(card.id);
        setMessage({ kind: 'success', text: '抢单成功，记得及时确认接单。' });
      } else {
        const r = await api.action(card.id, action.toLowerCase() as never);
        if (action === 'SETTLE' || action === 'CANCEL') {
          window.dispatchEvent(new Event('peergrab:wallet-changed'));
        }
        const already = r?.result ? ALREADY_TEXT[r.result] : undefined;
        setMessage({ kind: 'success', text: already ?? `${ACTION_TEXT[action]}成功` });
      }
      onChanged();
    } catch (e) {
      if (e instanceof BizError) {
        // 业务响应已到达（含 SETTLE_CONFLICT / REFUND_CONFLICT），幂等键弃用
        if (grabbing) pendingRequestId.current.delete(card.id);
        setMessage({ kind: 'error', text: `${e.message}（${e.code}）` });
      } else {
        // 网络错误：保留 requestId 供下一次重试复用
        setMessage({ kind: 'error', text: '网络错误，请稍后重试。' });
      }
    } finally {
      setBusy(null);
    }
  };

  if (card.availableActions.length === 0) return null;

  return (
    <div className={compact ? 'actions compact' : 'actions'}>
      {card.availableActions.map((a) => (
        <button key={a} type="button" disabled={busy !== null} onClick={() => run(a)}
                aria-busy={busy === a}
                className={`btn action-button btn-${a.toLowerCase()} ${busy === a ? 'busy' : ''}`}>
          {busy === a ? '处理中…' : (ACTION_TEXT[a] ?? a)}
        </button>
      ))}
      {message && (
        <span className={`action-msg action-feedback is-${message.kind}`}
          role={message.kind === 'error' ? 'alert' : 'status'}>{message.text}</span>
      )}
    </div>
  );
}
