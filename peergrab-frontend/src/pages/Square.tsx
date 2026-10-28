import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { newGrabRequestId } from '../grabRequests';
import { api, getRole } from '../api';
import type { ErrandCard } from '../api';
import ErrandCardView from '../components/ErrandCard';
import LoadingSkeleton from '../components/LoadingSkeleton';
import UiIcon from '../components/UiIcon';
import { grabV2WithToken, issueDemoCredentials, revokeCredentials } from '../api';
import type { DemoCredentials } from '../api';
import { demoPasswordFor } from '../identity';
import { showToast } from '../utils/toast';
import { subscribeWs } from '../ws';

interface GrabResultRow {
  label: string;
  ok: boolean;
  code: string;
  ms: number;
}

type Filter = 'all' | 'available';

/**
 * 任务广场：列表 + 抢单 + 两个已配置跑腿身份的并发演示。
 *
 * 并发抢单演示的实现方式：当前浏览器以两个跑腿身份向 v2 抢单端点
 * （POST /api/errands/{id}/grab-v2）同时发请求，每个请求带独立 requestId 与
 * 列表里的 expectedRound。于是「一人抢中、另一人拿 SLOT_FULL/GRAB_CONFLICT」
 * 直接来自后端真实裁决，而不是前端自说自话。
 *
 * 注意浏览器对同域并发连接有限制（HTTP/1.1 约 6 条），所以这只能演示
 * "少量并发下名额不超发"；真实的 2000 并发压测看后端的 SpikeLoadClient 报告，
 * 页面上对此做了如实标注——不把演示当压测。
 *
 * 另一个诚实的边界：同一用户重复抢会被幂等拦成 ALREADY_GRABBED，
 * 所以演示"多个不同跑腿抢"需要用不同的 token——这里通过"登录两个跑腿身份
 * 各拿一个 token、然后并发发请求"实现，与真实多人抢单等价。
 */
export default function Square() {
  const [cards, setCards] = useState<ErrandCard[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [grabLog, setGrabLog] = useState<GrabResultRow[]>([]);
  const [grabbing, setGrabbing] = useState(false);
  const [runner2001Password, setRunner2001Password] = useState(() => demoPasswordFor(2001));
  const [runner2002Password, setRunner2002Password] = useState(() => demoPasswordFor(2002));
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [nextCursor, setNextCursor] = useState('');
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<Filter>('all');
  const [expandedDemoId, setExpandedDemoId] = useState<string | null>(null);
  const targetRef = useRef<string | null>(null);
  // 前台加载（首次/手动刷新/加载更多）的版本号；后台静默刷新不参与，
  // 否则 30s 轮询或 WS 事件会把 loading 与分页游标一起重置。
  const requestVersionRef = useRef(0);

  const load = useCallback(() => {
    const version = ++requestVersionRef.current;
    setLoading(true);
    setLoadingMore(false);
    api.listByCursor(1)
      .then((page) => {
        if (version !== requestVersionRef.current) return;
        setCards(page.items);
        setNextCursor(page.nextCursor);
        setLoadError(null);
      })
      .catch((e: Error) => {
        if (version === requestVersionRef.current) setLoadError(e.message);
      })
      .finally(() => {
        if (version === requestVersionRef.current) setLoading(false);
      });
  }, []);

  useEffect(() => { load(); }, [load]);

  /**
   * 后台静默刷新：WS 事件和 30s 轮询共用。
   * 按 id 合并第一页，保留已加载的后续分页；既不重置 nextCursor，也不动前台 loading。
   */
  const silentRefresh = useCallback(() => {
    api.listByCursor(1)
      .then((page) => {
        setCards((current) => {
          const firstPageIds = new Set(page.items.map((card) => card.id));
          return [...page.items, ...current.filter((card) => !firstPageIds.has(card.id))];
        });
        setNextCursor((current) => current || page.nextCursor);
        setLoadError(null);
      })
      .catch(() => {
        // 后台刷新失败不打断当前列表，下一轮再试。
      });
  }, []);

  useEffect(() => {
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') silentRefresh();
    }, 30000);
    const unsubscribe = subscribeWs((event) => {
      if (event.type === 'errand.status' || event.type === 'notification.new') silentRefresh();
    });
    return () => {
      window.clearInterval(timer);
      unsubscribe();
    };
  }, [silentRefresh]);

  const loadMore = async () => {
    if (!nextCursor || loading || loadingMore) return;
    const cursor = nextCursor;
    const version = requestVersionRef.current;
    setLoadingMore(true);
    try {
      const page = await api.listByCursor(1, cursor);
      if (version !== requestVersionRef.current) return;
      setCards((current) => {
        const byId = new Map<string, ErrandCard>(current.map((card) => [card.id, card]));
        page.items.forEach((card) => byId.set(card.id, card));
        return [...byId.values()];
      });
      setNextCursor(page.nextCursor === cursor ? '' : page.nextCursor);
      setLoadError(null);
    } catch (e) {
      if (version === requestVersionRef.current) {
        setLoadError(e instanceof Error ? e.message : '网络错误');
      }
    } finally {
      if (version === requestVersionRef.current) setLoadingMore(false);
    }
  };

  // 仲裁员不参与发布/抢单：发布入口隐藏（抢单按钮由后端 availableActions 决定）
  const isArbitrator = getRole() === 'ARBITRATOR';
  const availableCount = cards.filter((card) => card.availableActions.includes('GRAB')).length;
  const visibleCards = useMemo(() => {
    const keyword = query.trim().toLocaleLowerCase();
    return cards.filter((card) => {
      if (filter === 'available' && !card.availableActions.includes('GRAB')) return false;
      return !keyword || `${card.title} ${card.type} ${card.id}`.toLocaleLowerCase().includes(keyword);
    });
  }, [cards, filter, query]);

  /** 并发抢单演示：登录两个已授权的跑腿身份，同时向 v2 抢单端点发请求 */
  const spikeGrab = async (errand: ErrandCard) => {
    const errandId = errand.id;
    const expectedRound = errand.round;
    targetRef.current = errandId;
    setGrabbing(true);
    setGrabLog([]);
    const users = [2001, 2002];
    const passwords = [runner2001Password, runner2002Password];
    const credentials: DemoCredentials[] = [];
    try {
      for (let i = 0; i < users.length; i++) {
        credentials.push(await issueDemoCredentials(users[i], passwords[i]));
      }

      // 对齐释放：所有请求同时发出；每个请求各自生成 requestId（同一请求重试才复用）
      const startedAt = performance.now();
      const results = await Promise.all(
        credentials.map(async (credential, index) => {
          const start = performance.now();
          try {
            const http = await grabV2WithToken(
              credential.token,
              errandId,
              expectedRound,
              newGrabRequestId(),
            );
            const code = http.result?.code ?? `HTTP_${http.httpStatus}`;
            return {
              label: `跑腿 ${users[index]}`,
              ok: code === 'OK' && http.result?.data?.grabbed === true,
              code,
              ms: Math.round(performance.now() - start),
            };
          } catch {
            return { label: `跑腿 ${users[index]}`, ok: false, code: 'NETWORK', ms: 0 };
          }
        }),
      );
      setGrabLog(results.sort((a, b) => Number(b.ok) - Number(a.ok)));
      const winners = results.filter((result) => result.ok).length;
      if (winners === 1) {
        showToast('success', `并发演示完成：一人抢中，总耗时 ${Math.round(performance.now() - startedAt)} ms。`);
      } else {
        showToast('error', `两人中有 ${winners} 人抢单成功，请查看演示结果与任务状态。`);
      }
      load();
    } catch (error) {
      showToast('error', `并发演示失败：${error instanceof Error ? error.message : '网络错误'}`);
    } finally {
      await Promise.all(credentials.map(revokeCredentials));
      setGrabbing(false);
    }
  };

  return (
    <div className="square-page" data-testid="square-page">
      <header className="page-intro square-intro">
        <div className="page-intro__copy">
          <span className="page-eyebrow">PEERGRAB MARKETPLACE</span>
          <h1>任务广场</h1>
          <p>看看校园里正在等待帮忙的事，找到适合自己的任务。</p>
        </div>
        <div className="page-intro__actions">
          <button type="button" className="btn btn-secondary" data-testid="square-refresh" onClick={load} disabled={loading || loadingMore}>
            <UiIcon name="refresh" size={16} /> {loading ? '正在刷新…' : '刷新任务'}
          </button>
          {!isArbitrator && <a className="btn btn-primary" href="#/publish"><UiIcon name="plus" size={16} /> 发布任务</a>}
        </div>
      </header>

      <div className="square-overview" aria-label="当前任务概况">
        <div className="square-overview__item">
          <span className="square-overview__label">已加载任务</span>
          <strong>{cards.length}</strong>
          <span className="square-overview__hint">正在等待跑腿</span>
        </div>
        <div className="square-overview__item">
          <span className="square-overview__label">我可接单</span>
          <strong>{availableCount}</strong>
          <span className="square-overview__hint">可直接开始</span>
        </div>
        <div className="square-overview__note">
          <span className="square-overview__note-icon"><UiIcon name="zap" size={20} /></span>
          <div><strong>从身边的小事开始</strong><p>接单后按任务进度完成，报酬由平台托管。</p></div>
        </div>
      </div>

      {loadError && <div className="banner warn" role="alert">任务加载失败：{loadError}</div>}
      {targetRef.current && grabLog.length > 0 && (
        <div className="race-result section-card" role="status">
          <div className="race-result__head">
            <strong>双人抢单演示结果</strong>
            <a href={`#/detail/${targetRef.current}`}>查看任务详情 →</a>
          </div>
          {grabLog.map((result) => (
            <div key={result.label} className={`race-result__row ${result.ok ? 'is-success' : 'is-failed'}`}>
              <span>{result.label}</span>
              <strong>{result.ok ? '抢单成功' : result.code}</strong>
              <span>{result.ms} ms</span>
            </div>
          ))}
          <p>这是浏览器内的小规模演示；高并发数据请查看项目压测报告。</p>
        </div>
      )}

      <section className="square-list" aria-labelledby="square-list-title">
        <div className="section-heading square-list__heading">
          <div>
            <span className="section-heading__eyebrow">DISCOVER</span>
            <h2 id="square-list-title">发现任务</h2>
            <p>浏览当前开放的任务，点击卡片可查看完整进度。</p>
          </div>
          <span className="square-list__count">显示 {visibleCards.length} / 已加载 {cards.length} 项</span>
        </div>

        <div className="square-toolbar">
          <label className="square-search">
            <span className="square-search__icon" aria-hidden="true">⌕</span>
            <input type="search" data-testid="square-search" value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="搜索已加载任务" aria-label="搜索已加载任务标题或编号" />
          </label>
          <div className="square-filter" aria-label="任务筛选">
            <button type="button" className={filter === 'all' ? 'active' : ''}
              aria-pressed={filter === 'all'} onClick={() => setFilter('all')}>全部任务</button>
            <button type="button" className={filter === 'available' ? 'active' : ''}
              aria-pressed={filter === 'available'} onClick={() => setFilter('available')}>我可接单</button>
          </div>
        </div>
        <p className="square-toolbar__hint">
          搜索和筛选仅作用于已加载的任务{nextCursor ? '；可继续加载更多任务。' : '。'}
        </p>

        {loading && cards.length === 0 ? (
          <LoadingSkeleton variant="cards" rows={4} label="正在加载任务…" />
        ) : loadError && cards.length === 0 ? (
          <div className="empty-state square-empty">
            <h3>暂时无法加载任务</h3>
            <p>请检查连接后重试。</p>
            <button type="button" className="btn btn-secondary" onClick={load}>重新加载</button>
          </div>
        ) : visibleCards.length === 0 ? (
          <div className="empty-state square-empty">
            <span className="empty-state__icon" aria-hidden="true">⌕</span>
            <h3>{cards.length === 0 ? '暂时没有开放任务' : '没有找到匹配的任务'}</h3>
            <p>{cards.length === 0
              ? (isArbitrator ? '仲裁员身份不参与发布任务。' : '发布第一条任务，让校园里的互助从这里开始。')
              : '试试其他关键词，或查看全部任务。'}</p>
            {cards.length === 0
              ? (!isArbitrator && <a className="btn btn-primary" href="#/publish"><UiIcon name="plus" size={16} /> 发布任务</a>)
              : <button type="button" className="btn btn-secondary" onClick={() => { setQuery(''); setFilter('all'); }}>清除筛选</button>}
          </div>
        ) : (
          <div className="card-grid square-grid" data-testid="square-list">
            {visibleCards.map((card) => (
              <div key={card.id} className="square-item">
                <ErrandCardView card={card} onChanged={() => load()} />
                {card.status === 'PUBLISHED' && (
                  <div className="race-demo">
                    <button type="button" className="race-demo__trigger"
                      aria-expanded={expandedDemoId === card.id}
                      onClick={() => { setExpandedDemoId(expandedDemoId === card.id ? null : card.id); setGrabLog([]); }}>
                      <span>体验双人同时抢单</span>
                      <span aria-hidden="true">{expandedDemoId === card.id ? '−' : '+'}</span>
                    </button>
                    {expandedDemoId === card.id && (
                      <div className="race-demo__content">
                        <p>两个演示跑腿身份的密码已自动填写，可直接发起并发抢单。</p>
                        <div className="race-demo__fields">
                          <label>跑腿 2001 密码
                            <input type="password" value={runner2001Password} autoComplete="off"
                              onChange={(event) => setRunner2001Password(event.target.value)} />
                          </label>
                          <label>跑腿 2002 密码
                            <input type="password" value={runner2002Password} autoComplete="off"
                              onChange={(event) => setRunner2002Password(event.target.value)} />
                          </label>
                        </div>
                        <button type="button" className="btn btn-secondary race-demo__submit"
                          disabled={grabbing || !runner2001Password || !runner2002Password}
                          onClick={() => spikeGrab(card)}>
                          {grabbing && targetRef.current === card.id ? '正在发起请求…' : '开始并发演示'}
                        </button>
                      </div>
                    )}
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
        {nextCursor && cards.length > 0 && (
          <div className="square-load-more">
            <button type="button" className="btn btn-secondary" onClick={loadMore}
              disabled={loading || loadingMore}>
              {loadingMore ? '正在加载…' : '加载更多任务'}
            </button>
          </div>
        )}
      </section>
    </div>
  );
}
