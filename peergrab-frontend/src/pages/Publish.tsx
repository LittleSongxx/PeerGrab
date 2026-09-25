import { useState } from 'react';
import type { FormEvent } from 'react';
import { api, BizError, getUserId, yuan } from '../api';

const PENDING_PUBLISH_KEY = 'peergrab_pending_publish';

/** Reuse the same key if a response was lost, including after a page reload. */
function requestIdFor(payload: object): string {
  const fingerprint = JSON.stringify({ userId: getUserId(), payload });
  try {
    const stored = JSON.parse(sessionStorage.getItem(PENDING_PUBLISH_KEY) || 'null') as
      { fingerprint?: string; requestId?: string } | null;
    if (stored?.fingerprint === fingerprint && stored.requestId) return stored.requestId;
  } catch {
    // Malformed browser storage cannot stop publication.
  }
  const requestId = crypto.randomUUID();
  try { sessionStorage.setItem(PENDING_PUBLISH_KEY, JSON.stringify({ fingerprint, requestId })); }
  catch { /* The current request still has its idempotency key. */ }
  return requestId;
}

const TYPE_TEXT: Record<string, string> = {
  DELIVERY: '跑腿配送', BUY: '代购', QUEUE: '代排队', OTHER: '其他',
};

export default function Publish() {
  const [title, setTitle] = useState('');
  const [reward, setReward] = useState('15');
  const [type, setType] = useState('DELIVERY');
  const [result, setResult] = useState<{ kind: 'success' | 'error'; text: string; id?: string } | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const parsedReward = Number(reward);
  const rewardCents = Number.isFinite(parsedReward) && parsedReward > 0 ? Math.round(parsedReward * 100) : 0;

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (submitting) return;
    setSubmitting(true);
    setResult(null);
    try {
      if (!title.trim()) throw new Error('请填写任务标题');
      if (title.trim().length > 64) throw new Error('任务标题最多 64 个字');
      if (!rewardCents || rewardCents <= 0) throw new Error('悬赏金额必须大于 0');
      if (!Number.isSafeInteger(rewardCents)) throw new Error('悬赏金额过大，请调整后重试');
      const payload = { title: title.trim(), rewardCents, slotTotal: 1, type };
      const r = await api.publish(payload, requestIdFor(payload));
      try { sessionStorage.removeItem(PENDING_PUBLISH_KEY); } catch { /* Browser storage is optional. */ }
      window.dispatchEvent(new Event('peergrab:wallet-changed'));
      setResult({ kind: 'success', text: `发布成功，${yuan(rewardCents)} 已托管，等待跑腿接单。`, id: String(r.errandId) });
      setTitle('');
    } catch (e) {
      setResult({ kind: 'error', text: e instanceof BizError ? `${e.message}（${e.code}）` : (e as Error).message });
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="publish-page page-stack">
      <header className="page-intro">
        <div>
          <span className="page-eyebrow">发起校园互助</span>
          <h1>发布任务</h1>
          <p>说明你的需求、设置悬赏，让附近的同学来帮忙。</p>
        </div>
        <a className="btn btn-secondary page-intro-action" href="#/mine">查看我的任务</a>
      </header>

      <div className="publish-layout">
        <form className="section-card publish-form" onSubmit={(event) => void submit(event)}>
          <div className="section-heading">
            <div>
              <span className="section-kicker">任务信息</span>
              <h2>填写任务详情</h2>
              <p>清楚的标题和合适的悬赏，能让同学更快了解你的需求。</p>
            </div>
          </div>

          <div className="publish-fields">
            <div className="publish-field">
              <label htmlFor="publish-title">任务标题 <span className="required-mark">*</span></label>
              <input id="publish-title" value={title} maxLength={64} required
                     onChange={(event) => { setTitle(event.target.value); setResult(null); }}
                     placeholder="例如：帮我从三食堂带一份饭到 6 号楼" />
              <div className="publish-field-meta"><span>一句话说明要做什么</span><span>{title.length}/64</span></div>
            </div>

            <div className="publish-field">
              <label htmlFor="publish-type">任务类型 <span className="required-mark">*</span></label>
              <select id="publish-type" value={type} onChange={(event) => { setType(event.target.value); setResult(null); }}>
                <option value="DELIVERY">跑腿配送</option>
                <option value="BUY">代购</option>
                <option value="QUEUE">代排队</option>
                <option value="OTHER">其他</option>
              </select>
              <span className="publish-field-hint">选择最贴近任务内容的类型</span>
            </div>

            <div className="publish-field">
              <label htmlFor="publish-reward">悬赏金额 <span className="required-mark">*</span></label>
              <div className="publish-money-input">
                <span aria-hidden="true">¥</span>
                <input id="publish-reward" type="number" min="0.01" step="0.01" inputMode="decimal"
                       value={reward} required onChange={(event) => { setReward(event.target.value); setResult(null); }} />
                <span>元</span>
              </div>
              <span className="publish-field-hint">发布时从可用余额托管，完成后结算给跑腿</span>
            </div>
          </div>

          {result && (
            <div className={result.kind === 'success' ? 'banner info publish-result' : 'banner warn publish-result'} role="status">
              <strong>{result.kind === 'success' ? '发布成功' : '发布失败'}</strong>
              <span>{result.text}</span>
              {result.id && <a href={`#/detail/${result.id}`}>查看任务详情 ↗</a>}
            </div>
          )}

          <div className="publish-form-footer">
            <p>点击发布即确认将悬赏金额用于任务托管。</p>
            <button className="btn btn-primary publish-submit" type="submit" disabled={submitting}>
              {submitting ? '发布中…' : '发布并托管'} <span aria-hidden="true">↗</span>
            </button>
          </div>
        </form>

        <aside className="publish-sidebar">
          <div className="section-card publish-summary">
            <span className="section-kicker">发布预览</span>
            <h2>本次悬赏</h2>
            <strong className="publish-summary-amount">{yuan(rewardCents)}</strong>
            <div className="publish-summary-row"><span>任务类型</span><strong>{TYPE_TEXT[type]}</strong></div>
            <div className="publish-summary-row"><span>接单名额</span><strong>1 人</strong></div>
            <p>发布后，同学可以在任务广场看到并抢接任务。</p>
          </div>
          <div className="section-card publish-steps">
            <span className="section-kicker">接下来会发生什么</span>
            <ol>
              <li><span>01</span><div><strong>发布并托管</strong><p>悬赏从钱包余额扣除，交由平台托管。</p></div></li>
              <li><span>02</span><div><strong>同学接单</strong><p>确认接单后，跑腿开始执行任务。</p></div></li>
              <li><span>03</span><div><strong>完成并结算</strong><p>送达后由你确认完成，悬赏结算给跑腿。</p></div></li>
            </ol>
          </div>
        </aside>
      </div>
    </div>
  );
}
