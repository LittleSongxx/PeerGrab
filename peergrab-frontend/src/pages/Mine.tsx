import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../api';
import type { ErrandCard } from '../api';
import ErrandCardView from '../components/ErrandCard';

type MineTab = 'PUBLISHED_BY_ME' | 'GRABBED_BY_ME';
const PAGE_SIZE = 20;

export default function Mine() {
  const [tab, setTab] = useState<MineTab>('PUBLISHED_BY_ME');
  const [cards, setCards] = useState<ErrandCard[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [moreError, setMoreError] = useState<string | null>(null);
  const requestId = useRef(0);

  const load = useCallback(async () => {
    const currentRequest = ++requestId.current;
    setLoading(true);
    setError(null);
    setMoreError(null);
    setLoadingMore(false);
    try {
      const nextCards = await api.mine(tab, 0, PAGE_SIZE);
      if (currentRequest === requestId.current) {
        setCards(nextCards);
        setPage(0);
        setHasMore(nextCards.length === PAGE_SIZE);
      }
    } catch (cause) {
      if (currentRequest === requestId.current) {
        setError(cause instanceof Error ? cause.message : '任务加载失败，请稍后重试');
      }
    } finally {
      if (currentRequest === requestId.current) setLoading(false);
    }
  }, [tab]);

  const loadMore = async () => {
    if (loading || loadingMore || !hasMore) return;
    const currentRequest = requestId.current;
    const nextPage = page + 1;
    setLoadingMore(true);
    setMoreError(null);
    try {
      const nextCards = await api.mine(tab, nextPage, PAGE_SIZE);
      if (currentRequest !== requestId.current) return;
      setCards((current) => {
        const seen = new Set(current.map((card) => card.id));
        return [...current, ...nextCards.filter((card) => !seen.has(card.id))];
      });
      setPage(nextPage);
      setHasMore(nextCards.length === PAGE_SIZE);
    } catch (cause) {
      if (currentRequest === requestId.current) {
        setMoreError(cause instanceof Error ? cause.message : '后续任务加载失败，请重试');
      }
    } finally {
      if (currentRequest === requestId.current) setLoadingMore(false);
    }
  };

  useEffect(() => {
    void load();
    return () => { requestId.current += 1; };
  }, [load]);

  return (
    <div className="mine-page page-stack">
      <header className="page-intro">
        <div>
          <span className="page-eyebrow">我的校园跑腿</span>
          <h1>我的任务</h1>
          <p>发布与接下的任务都在这里，随时掌握最新进展。</p>
        </div>
        <a className="btn btn-primary page-intro-action" href="#/publish">发布新任务 <span aria-hidden="true">↗</span></a>
      </header>

      <section className="mine-section" aria-label="任务列表">
        <div className="mine-toolbar">
          <div className="tabs mine-tabs" role="group" aria-label="我的任务类型">
            <button type="button" aria-pressed={tab === 'PUBLISHED_BY_ME'}
                    className={tab === 'PUBLISHED_BY_ME' ? 'tab active' : 'tab'}
                    onClick={() => setTab('PUBLISHED_BY_ME')}>我发布的</button>
            <button type="button" aria-pressed={tab === 'GRABBED_BY_ME'}
                    className={tab === 'GRABBED_BY_ME' ? 'tab active' : 'tab'}
                    onClick={() => setTab('GRABBED_BY_ME')}>我接下的</button>
          </div>
          {!loading && !error && (
            <span className="mine-count">{hasMore ? '已显示' : '共'} {cards.length} 个任务</span>
          )}
        </div>

        <div className="mine-results">
          {loading && <div className="loading-state" role="status">正在加载任务…</div>}
          {!loading && error && (
            <div className="empty-state" role="alert">
              <span className="empty-state-icon" aria-hidden="true">!</span>
              <h2>暂时无法获取任务</h2>
              <p>{error}</p>
              <button className="btn btn-primary" type="button" onClick={() => void load()}>重新加载</button>
            </div>
          )}
          {!loading && !error && cards.length === 0 && (
            <div className="empty-state">
              <span className="empty-state-icon" aria-hidden="true">◎</span>
              <h2>{tab === 'PUBLISHED_BY_ME' ? '还没有发布任务' : '还没有接下任务'}</h2>
              <p>{tab === 'PUBLISHED_BY_ME' ? '把需要帮忙的事交给校园跑腿，从第一个任务开始。' : '去任务广场看看，寻找适合你的跑腿任务。'}</p>
              <a className="btn btn-primary" href={tab === 'PUBLISHED_BY_ME' ? '#/publish' : '#/'}>
                {tab === 'PUBLISHED_BY_ME' ? '发布任务' : '逛逛任务广场'}
              </a>
            </div>
          )}
          {!loading && !error && cards.length > 0 && (
            <>
              <div className="card-grid mine-card-grid">
                {cards.map((card) => <ErrandCardView key={card.id} card={card} onChanged={load} />)}
              </div>
              {(hasMore || moreError) && (
                <div className="mine-load-more">
                  {moreError && <p className="banner warn" role="alert">{moreError}</p>}
                  <button className="btn btn-secondary" type="button" disabled={loadingMore}
                          onClick={() => void loadMore()}>
                    {loadingMore ? '加载中…' : moreError ? '重试加载' : '加载更多任务'}
                  </button>
                </div>
              )}
            </>
          )}
        </div>
      </section>
    </div>
  );
}
