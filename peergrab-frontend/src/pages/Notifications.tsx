import { useCallback, useEffect, useState } from 'react';
import { api } from '../api';

const TYPE_TEXT: Record<string, string> = {
  SETTLED: '结算完成', REFUNDED: '退款完成', ARBITRATED: '仲裁结果',
};

export default function Notifications() {
  const [items, setItems] = useState<Awaited<ReturnType<typeof api.notifications>>>([]);
  const [filter, setFilter] = useState<'all' | 'unread'>('all');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setItems(await api.notifications());
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '消息加载失败，请稍后重试');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

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
    <div className="notice-page page-stack">
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
        {loading && items.length === 0 && <div className="loading-state" role="status">正在加载消息…</div>}
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
      </section>
    </div>
  );
}
