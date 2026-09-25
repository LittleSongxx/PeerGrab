import { useCallback, useEffect, useState } from 'react';
import { api, yuan, STATUS_TEXT, BizError } from '../api';
import type { ErrandCard, StatusChange } from '../api';
import ActionButtons from '../components/ActionButtons';
import { subscribeWs } from '../ws';

interface Props {
  errandId: string;
  onIdentityChange: (id: number) => void;
}

const TERMINAL = new Set(['SETTLED', 'CLOSED', 'CANCELLED', 'REFUNDED']);
const ROLE_TEXT: Record<string, string> = {
  PUBLISHER: '发单人', CURRENT_RUNNER: '当前跑腿', ARBITRATOR: '仲裁员', VIEWER: '访客',
};
const TYPE_TEXT: Record<string, string> = {
  DELIVERY: '代取代送', BUY: '代买', QUEUE: '代排队', OTHER: '其他任务',
};
const STATUS_HINT: Record<string, string> = {
  DRAFT: '任务尚未公开，等待发布。',
  PUBLISHED: '任务已开放，正在等待跑腿接单。',
  LOCKED: '已有跑腿抢中任务，等待确认接单。',
  ACCEPTED: '跑腿已确认接单，等待取货。',
  PICKED_UP: '任务进行中，等待跑腿送达。',
  DELIVERED: '跑腿已送达，等待发单人确认完成。',
  SETTLED: '任务已完成，悬赏已结算。',
  CANCELLED: '任务已取消，悬赏将退回发单人。',
  CLOSED: '任务已关闭。',
  DISPUTED: '任务正在仲裁，资金暂时保持托管。',
  REFUNDED: '任务已退款，悬赏已退回发单人。',
};

export default function Detail({ errandId, onIdentityChange }: Props) {
  const [card, setCard] = useState<ErrandCard | null>(null);
  const [timeline, setTimeline] = useState<StatusChange[]>([]);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [actionNotice, setActionNotice] = useState<{ kind: 'success' | 'error'; text: string } | null>(null);
  const [arbitrating, setArbitrating] = useState(false);
  const [arbitrationBusy, setArbitrationBusy] = useState(false);

  const load = useCallback(() => {
    api.detail(errandId)
      .then((item) => { setCard(item); setDetailError(null); })
      .catch((e: Error) => setDetailError(e.message));
    api.timeline(errandId).then(setTimeline).catch(() => {});
  }, [errandId]);

  useEffect(() => {
    setCard(null);
    setTimeline([]);
    setDetailError(null);
    setActionNotice(null);
    setArbitrating(false);
  }, [errandId]);

  useEffect(() => {
    load();
    // P5：WS 推送驱动刷新（worker 触发的变更推不到，靠低频轮询兜底）
    const unsub = subscribeWs((event) => {
      if (event.type === 'errand.status'
          && String(event.payload.errandId) === String(errandId)) {
        load();
      }
    });
    // 非终态时保留低频轮询（10s）：覆盖 worker 触发的流转/自动结算
    let t: number | undefined;
    if (card && !TERMINAL.has(card.status)) {
      t = window.setInterval(load, 10000);
    }
    return () => { unsub(); if (t) window.clearInterval(t); };
  }, [load, card?.status, errandId]);

  const arbitrate = async (favor: 'RUNNER' | 'PUBLISHER') => {
    setArbitrationBusy(true);
    setActionNotice(null);
    try {
      const r = await api.arbitrate(errandId, favor);
      window.dispatchEvent(new Event('peergrab:wallet-changed'));
      setActionNotice({ kind: 'success', text: `仲裁完成：${r.result === 'SETTLED_TO_RUNNER' ? '支持跑腿，资金已结算' : '支持发单人，资金已退回'}` });
      setArbitrating(false);
      load();
    } catch (e) {
      setActionNotice({ kind: 'error', text: e instanceof BizError ? `${e.message}（${e.code}）` : '网络错误，请稍后重试。' });
    } finally {
      setArbitrationBusy(false);
    }
  };

  if (!card) return (
    <div className="empty-state detail-empty" role="status">
      <h2>{detailError ? '暂时无法打开任务' : '正在加载任务…'}</h2>
      {detailError && <><p>{detailError}</p><button type="button" className="btn btn-secondary" onClick={load}>重试</button></>}
    </div>
  );

  return (
    <div className="detail-page">
      <a className="detail-back" href="#/square">← 返回任务广场</a>
      <header className="page-intro detail-intro">
        <div className="page-intro__copy">
          <span className="page-eyebrow">TASK DETAILS · #{String(card.id).slice(-8)}</span>
          <h1>{card.title}</h1>
          <p>{STATUS_HINT[card.status] ?? '查看这条任务的最新进度与可执行操作。'}</p>
        </div>
        <span className={`status status-${card.status}`}>{STATUS_TEXT[card.status] ?? card.status}</span>
      </header>

      {detailError && <div className="banner warn" role="alert">更新任务失败：{detailError}</div>}
      {actionNotice && (
        <div className={`banner ${actionNotice.kind === 'error' ? 'warn' : 'info'}`}
          role={actionNotice.kind === 'error' ? 'alert' : 'status'}>{actionNotice.text}</div>
      )}

      <section className="detail-summary" aria-label="任务概览">
        <div className="detail-summary__reward">
          <span>悬赏报酬</span>
          <strong className="reward">{yuan(card.rewardCents)}</strong>
          <small>发布时已托管</small>
        </div>
        <div className="detail-summary__item">
          <span>任务类型</span>
          <strong>{TYPE_TEXT[card.type] ?? card.type}</strong>
        </div>
        <div className="detail-summary__item">
          <span>接单名额</span>
          <strong>{card.slotTaken} / {card.slotTotal}</strong>
          <small>已接 / 总名额</small>
        </div>
        <div className="detail-summary__item">
          <span>当前身份</span>
          <strong>{ROLE_TEXT[card.role] ?? card.role}</strong>
        </div>
      </section>

      <div className="detail-grid">
        <div className="detail-main">
          <section className="section-card detail-actions" aria-labelledby="detail-actions-title">
            <div className="section-heading">
              <div>
                <span className="section-heading__eyebrow">NEXT STEP</span>
                <h2 id="detail-actions-title">下一步操作</h2>
                <p>页面会根据任务进度和你的身份显示可用操作。</p>
              </div>
            </div>
            {card.availableActions.length > 0
              ? <ActionButtons card={card} onChanged={load} onRequestArbitration={() => setArbitrating(true)} />
              : <p className="detail-actions__idle">当前无需你操作；有新进度时，这里会显示下一步。</p>}

            {arbitrating && card.availableActions.includes('ARBITRATE') && (
              <div className="arbitrate-zone">
                <div className="arbitrate-zone__heading">
                  <strong>选择仲裁结果</strong>
                  <button type="button" className="link-btn" onClick={() => setArbitrating(false)} disabled={arbitrationBusy}>返回</button>
                </div>
                <p>请核对事实后选择托管资金去向。提交后将完成本次仲裁。</p>
                <div className="arbitrate-choose">
                  <button type="button" className="btn btn-deliver" disabled={arbitrationBusy}
                    onClick={() => arbitrate('RUNNER')}>支持跑腿 · 结算悬赏</button>
                  <button type="button" className="btn btn-cancel" disabled={arbitrationBusy}
                    onClick={() => arbitrate('PUBLISHER')}>支持发单人 · 全额退款</button>
                </div>
              </div>
            )}
          </section>

          <section className="section-card detail-facts" aria-labelledby="detail-facts-title">
            <div className="section-heading">
              <div><span className="section-heading__eyebrow">INFORMATION</span><h2 id="detail-facts-title">任务信息</h2></div>
            </div>
            <dl className="detail-facts__list">
              <div><dt>任务编号</dt><dd>#{card.id}</dd></div>
              <div><dt>发单人</dt><dd>#{String(card.publisherId).slice(-6)}</dd></div>
              <div><dt>当前跑腿</dt><dd>{card.grabberId && card.grabberId !== '-1' ? `#${String(card.grabberId).slice(-6)}` : '等待接单'}</dd></div>
              <div><dt>流转轮次</dt><dd>第 {card.round + 1} 轮</dd></div>
            </dl>
          </section>
        </div>

        <aside className="section-card detail-timeline" aria-labelledby="detail-timeline-title">
          <div className="section-heading">
            <div><span className="section-heading__eyebrow">ACTIVITY</span><h2 id="detail-timeline-title">任务进度</h2>
              <p>每一步状态变化都会记录在这里。</p></div>
          </div>
          {timeline.length === 0 ? <p className="detail-timeline__empty">暂无进度记录。</p> : (
            <ol className="detail-timeline__list">
              {timeline.map((change, index) => (
                <li key={`${change.time}-${index}`} className="detail-timeline__item">
                  <span className="detail-timeline__marker" aria-hidden="true" />
                  <div className="detail-timeline__content">
                    <strong>{STATUS_TEXT[change.to] ?? change.to}</strong>
                    <p>{STATUS_TEXT[change.from] ?? change.from} → {STATUS_TEXT[change.to] ?? change.to}</p>
                    <div className="detail-timeline__meta">
                      <time dateTime={change.time}>{new Date(change.time).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })}</time>
                      <span>{change.operatorId === '-1' ? '系统更新' : `用户 #${String(change.operatorId).slice(-6)}`}</span>
                      {change.round > 0 && <span>第 {change.round + 1} 轮</span>}
                    </div>
                  </div>
                </li>
              ))}
            </ol>
          )}
          <p className="detail-timeline__hint">抢单后若未及时确认，系统会自动释放名额并重新开放任务。</p>
        </aside>
      </div>
    </div>
  );
}
