import { useCallback, useEffect, useState } from 'react';
import { api, getUserId } from '../api';
import type { CreditSelf, CreditRankEntry } from '../api';

/**
 * 信用分页：我的分数 + 30 天事件流水 + 校区排行榜。
 * 分数规则透明展示——让用户知道"为什么是这个分"。
 */
export default function Credit() {
  const [self, setSelf] = useState<CreditSelf | null>(null);
  const [ranking, setRanking] = useState<CreditRankEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [nextSelf, nextRanking] = await Promise.all([
        api.creditSelf(),
        api.creditRanking(1, 20),
      ]);
      setSelf(nextSelf);
      setRanking(nextRanking);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '信用数据加载失败，请稍后重试');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const myRank = ranking.findIndex((entry) => entry.userId === String(getUserId()));

  return (
    <div className="credit-page page-stack">
      <header className="page-intro">
        <div>
          <span className="page-eyebrow">信用成长</span>
          <h1>我的信用分</h1>
          <p>每一次认真完成任务，都在积累你的校园信用。</p>
        </div>
        <button className="btn btn-secondary page-intro-action" type="button" onClick={() => void load()} disabled={loading}>
          {loading ? '刷新中…' : '刷新数据'}
        </button>
      </header>

      {error && (
        <div className="banner warn" role="alert">
          {error} <button className="link-btn" type="button" onClick={() => void load()}>重试</button>
        </div>
      )}

      <section className="credit-overview section-card" aria-label="我的信用概览">
        <div className="credit-score-block">
          <span className="section-kicker">当前信用分</span>
          <div className="credit-score">{self ? self.score : '—'}<span>分</span></div>
          <p>{myRank >= 0 ? `校区排名第 ${myRank + 1} 位` : '完成任务，积累你的第一笔信用记录'}</p>
        </div>
        <div className="credit-rules">
          <h2>信用如何计算？</h2>
          <p>按最近 {self?.windowDays ?? 30} 天的任务记录动态计算，分数会影响抢单资格与流转优先级。</p>
          <div className="credit-rule-list">
            <span><strong className="credit">+2</strong> 完成结算</span>
            <span><strong className="debit">-5</strong> 超时未确认</span>
            <span><strong className="debit">-8</strong> 争议败诉</span>
          </div>
        </div>
      </section>

      <div className="credit-content-grid">
        <section className="section-card credit-events" aria-labelledby="credit-events-title">
          <div className="section-heading">
            <div>
              <span className="section-kicker">近期动态</span>
              <h2 id="credit-events-title">信用记录</h2>
            </div>
            {self && <span className="section-count">近 {self.windowDays} 天</span>}
          </div>
          {loading && !self && <div className="loading-state" role="status">正在加载信用记录…</div>}
          {!loading && self?.events.length === 0 && (
            <div className="empty-state credit-empty">
              <span className="empty-state-icon" aria-hidden="true">◎</span>
              <h3>暂无信用记录</h3>
              <p>完成任务后，这里会记录每次分数变化。</p>
            </div>
          )}
          {self?.events.map((event, index) => (
            <div key={`${event.refId}-${event.type}-${index}`} className="credit-event">
              <span className={event.delta >= 0 ? 'credit-event-delta is-positive' : 'credit-event-delta is-negative'}>
                {event.delta > 0 ? '+' : ''}{event.delta}
              </span>
              <div className="credit-event-content">
                <strong>{event.description}</strong>
                <time dateTime={event.time}>{new Date(event.time).toLocaleString('zh-CN')}</time>
              </div>
            </div>
          ))}
        </section>

        <section className="section-card credit-ranking" aria-labelledby="credit-ranking-title">
          <div className="section-heading">
            <div>
              <span className="section-kicker">校园榜单</span>
              <h2 id="credit-ranking-title">校区排行榜</h2>
            </div>
            <span className="section-count">Top 20</span>
          </div>
          {loading && ranking.length === 0 && <div className="loading-state" role="status">正在加载排行榜…</div>}
          {!loading && ranking.length === 0 && (
            <div className="empty-state credit-empty">
              <span className="empty-state-icon" aria-hidden="true">☆</span>
              <h3>榜单正在等待第一位跑腿</h3>
              <p>完成一次结算，即可参与排名。</p>
            </div>
          )}
          {ranking.length > 0 && (
            <ol className="credit-rank-list">
              {ranking.map((entry, index) => (
                <li key={entry.userId} className={entry.userId === String(getUserId()) ? 'credit-rank-row is-me' : 'credit-rank-row'}>
                  <span className="credit-rank-position">{index + 1}</span>
                  <span className="credit-rank-user">{entry.userId === String(getUserId()) ? '我' : `用户 #${entry.userId.slice(-6)}`}</span>
                  <strong>{entry.score} <small>分</small></strong>
                </li>
              ))}
            </ol>
          )}
        </section>
      </div>
    </div>
  );
}
