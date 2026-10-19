import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../api';
import LoadingSkeleton from '../components/LoadingSkeleton';
import { subscribeWs } from '../ws';

const TYPE_TEXT: Record<string, string> = {
  SETTLED: '结算完成', REFUNDED: '退款完成', TRANSFERRED: '递补通知',
  REVERTED: '任务重新开放', ARBITRATED: '仲裁结果',
};
const PAGE_SIZE = 20;

type Notice = Awaited<ReturnType<typeof api.notifications>>[number];

export default function Notifications() {
  const [items, setItems] = useState<Notice[]>([]);
  const [filter, setFilter] = useState<'all' | 'unread'>('all');
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [hasMore, setHasMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [moreError, setMoreError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const requestVersion = useRef(0);
  const pageCount = useRef(1);

  /**
   * 拉第一页。静默刷新用于轮询/WS：按 id 合并，已有条目的 read=true 不被旧快照覆盖，
   * 已加载的后续分页也不会被一次刷新清空。
   */
  const load = useCallback(async (silent = false) => {
    const version = ++requestVersion.current;
    if (!silent) setLoading(true);
    setError(null);
    try {
      const page = await api.notifications(0, PAGE_SIZE);
      if (version !== requestVersion.current) return;
      if (!silent) pageCount.current = 1;
      setItems((current) => {
        if (!silent || current.length === 0) return page;
        const previousById = new Map(current.map((item) => [item.id, item]));
        const merged = page.map((fresh) => {
          const previous = previousById.get(fresh.id);
          return previous?.read && !fresh.read ? { ...fresh, read: true } : fresh;
        });
        const firstPageIds = new Set(merged.map((item) => item.id));
        return [...merged, ...current.filter((item) => !firstPageIds.has(item.id))];
      });
      setHasMore(page.length === PAGE_SIZE);
    } catch (cause) {
      if (version === requestVersion.current && !silent) {
        setError(cause instanceof Error ? cause.message : '消息加载失败，请稍后重试');
      }
    } finally {
      if (version === requestVersion.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
    const poll = window.setInterval(() => {
      if (document.visibilityState === 'visible') void load(true);
    }, 30000);
    const unsubscribe = subscribeWs((event) => {
      if (event.type === 'notification.new') void load(true);
    });
    const onRead = () => void load(true);
    window.addEventListener('peergrab:notification-read', onRead);
    return () => {
      window.clearInterval(poll);
      unsubscribe();
      window.removeEventListener('peergrab:notification-read', onRead);
    };
  }, [load]);

  const loadMore = async () => {
    if (!hasMore || loading || loadingMore) return;
    const version = requestVersion.current;
    const nextPage = pageCount.current;
    setLoadingMore(true);
    setMoreError(null);
    try {
      const page = await api.notifications(nextPage, PAGE_SIZE);
      if (version !== requestVersion.current) return;
      pageCount.current = nextPage + 1;
      setItems((current) => {
        const seen = new Set(current.map((item) => item.id));
        return [...current, ...page.filter((item) => !seen.has(item.id))];
      });
      setHasMore(page.length === PAGE_SIZE);
    } catch (cause) {
      if (version === requestVersion.current) {
        setMoreError(cause instanceof Error ? cause.message : '更多消息加载失败，请重试');
      }
    } finally {
      if (version === requestVersion.current) setLoadingMore(false);
    }
  };

  const markRead = async (id: string) => {
    setBusy(id);
    setError(null);
    try {
      await api.markNotificationRead(id);
      setItems((current) => current.map((item) => item.id === id ? { ...item, read: true } : item));
      window.dispatchEvent(new Event('peergrab:notification-read'));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '标记已读失败，请重试');
    } finally {
      setBusy(null);
    }
  };

  const markAllRead = async () => {
    const unread = items.filter((item) => !item.read);
    if (unread.length === 0) return;
    setBusy('all');
    setError(null);
    const results = await Promise.allSettled(unread.map((item) => api.markNotificationRead(item.id)));
    const readIds = new Set(unread.filter((_, index) => results[index].status === 'fulfilled').map((item) => item.id));
    setItems((current) => current.map((item) => readIds.has(item.id) ? { ...item, read: true } : item));
    if (readIds.size > 0) window.dispatchEvent(new Event('peergrab:notification-read'));
    if (readIds.size < unread.length) setError('部分消息标记失败，请重试。');
    setBusy(null);
  };

  const unreadCount = items.filter((item) => !item.read).length;
  const visibleItems = filter === 'unread' ? items.filter((item) => !item.read) : items;

  return (
    <div className="notice-page page-stack" data-testid="notifications-page">
      <header className="page-intro">
        <div>
          <span className="page-eyebrow">消息中心</span>
          <h1>站内消息</h1>
          <p>任务结算、退款和仲裁结果会及时通知你。</p>
        </div>
        {unreadCount > 0 && (
          <button className="btn btn-secondary page-intro-action" type="button" disabled={busy !== null}
                  onClick={() => void markAllRead()}>{busy === 'all' ? '处理中…' : '全部标为已读'}</button>
        )}
      </header>

      <section className="section-card notice-center" aria-label="消息列表">
        <div className="notice-toolbar">
          <div className="tabs notice-tabs" role="group" aria-label="消息筛选">
            <button className={filter === 'all' ? 'tab active' : 'tab'} type="button"
                    aria-pressed={filter === 'all'} onClick={() => setFilter('all')}>全部 <span>{items.length}</span></button>
            <button className={filter === 'unread' ? 'tab active' : 'tab'} type="button"
                    aria-pressed={filter === 'unread'} onClick={() => setFilter('unread')}>未读 <span>{unreadCount}</span></button>
          </div>
          <button className="link-btn notice-refresh" type="button" onClick={() => void load()} disabled={loading}>
            {loading ? '刷新中…' : '刷新消息'}
          </button>
        </div>

        {error && <div className="banner warn" role="alert">{error}</div>}
        {loading && items.length === 0 && <LoadingSkeleton variant="rows" rows={4} label="正在加载消息…" />}
        {!loading && !error && visibleItems.length === 0 && (
          <div className="empty-state notice-empty">
            <span className="empty-state-icon" aria-hidden="true">✉</span>
            <h2>{filter === 'unread' ? '所有消息都已读' : '暂时没有消息'}</h2>
            <p>{filter === 'unread' ? '新消息到来时会显示在这里。' : '有新的任务进展时，我们会在这里通知你。'}</p>
          </div>
        )}
        <div className="notice-list">
          {visibleItems.map((item) => (
            <article key={item.id} className={item.read ? 'notice-item' : 'notice-item is-unread'}>
              <span className="notice-dot" aria-label={item.read ? '已读' : '未读'} />
              <div className="notice-body">
                <div className="notice-meta">
                  <span className={`notif-type notif-${item.type.toLowerCase()} notice-type`}>{TYPE_TEXT[item.type] ?? item.type}</span>
                  <time dateTime={item.time}>{new Date(item.time).toLocaleString('zh-CN')}</time>
                </div>
                <a className="notice-content" href={`#/detail/${item.errandId}`}
                   onClick={() => { if (!item.read && busy === null) void markRead(item.id); }}>
                  {item.content}
                </a>
                <div className="notice-footer">
                  <a href={`#/detail/${item.errandId}`}
                     onClick={() => { if (!item.read && busy === null) void markRead(item.id); }}>
                    查看关联任务 <span aria-hidden="true">↗</span>
                  </a>
                  {!item.read && (
                    <button className="link-btn" type="button" disabled={busy !== null}
                            onClick={() => void markRead(item.id)}>{busy === item.id ? '处理中…' : '标为已读'}</button>
                  )}
                </div>
              </div>
            </article>
          ))}
        </div>

        {(hasMore || moreError) && (
          <div className="mine-load-more">
            {moreError && <p className="banner warn" role="alert">{moreError}</p>}
            <button className="btn btn-secondary" type="button" disabled={loadingMore}
                    onClick={() => void loadMore()}>
              {loadingMore ? '加载中…' : moreError ? '重试加载' : '加载更多消息'}
            </button>
            {hasMore && <p className="notice-load-note">“全部标为已读”只处理当前已加载消息。</p>}
          </div>
        )}
      </section>
    </div>
  );
}
