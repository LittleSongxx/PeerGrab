import { useState } from 'react';
import { api, ACTION_TEXT, BizError } from '../api';
import type { ErrandCard } from '../api';

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

  const run = async (action: string) => {
    if (action === 'ARBITRATE') {
      if (onRequestArbitration) onRequestArbitration();
      else location.hash = `#/detail/${card.id}`;
      return;
    }
    setBusy(action);
    setMessage(null);
    try {
      if (action === 'GRAB') {
        await api.grab(card.id);
        setMessage({ kind: 'success', text: '抢单成功，记得及时确认接单。' });
      } else {
        await api.action(card.id, action.toLowerCase() as never);
        if (action === 'SETTLE' || action === 'CANCEL') {
          window.dispatchEvent(new Event('peergrab:wallet-changed'));
        }
        setMessage({ kind: 'success', text: `${ACTION_TEXT[action]}成功` });
      }
      onChanged();
    } catch (e) {
      if (e instanceof BizError) {
        setMessage({ kind: 'error', text: `${e.message}（${e.code}）` });
      } else {
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
