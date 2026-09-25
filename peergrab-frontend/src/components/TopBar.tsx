import { useEffect, useState } from 'react';
import { api, yuan } from '../api';
import { subscribeWs, isWsAvailable } from '../ws';
import { IDENTITIES } from '../identity';
import UiIcon, { type UiIconName } from './UiIcon';

interface Props {
  route: string;
  userId: number;
  onLogout: () => void;
}

const navigation: { href: string; label: string; icon: UiIconName; group: 'task' | 'account' }[] = [
  { href: '/square', label: '任务广场', icon: 'grid', group: 'task' },
  { href: '/publish', label: '发布任务', icon: 'plus', group: 'task' },
  { href: '/mine', label: '我的任务', icon: 'tasks', group: 'task' },
  { href: '/wallet', label: '我的钱包', icon: 'wallet', group: 'account' },
  { href: '/credit', label: '信用中心', icon: 'award', group: 'account' },
  { href: '/notifications', label: '消息通知', icon: 'bell', group: 'account' },
];

export default function TopBar({ route, userId, onLogout }: Props) {
  const [unread, setUnread] = useState(0);
  const [balance, setBalance] = useState<number | null>(null);
  const [menuOpen, setMenuOpen] = useState(false);

  useEffect(() => {
    api.unread().then((result) => setUnread(result.count)).catch(() => {});
    const refreshBalance = () => { api.wallet().then((wallet) => setBalance(wallet.availableCents)).catch(() => {}); };
    refreshBalance();

    const unsubscribe = subscribeWs((event) => {
      if (event.type === 'notification.new') {
        setUnread((current) => current + 1);
        refreshBalance();
      }
    });
    const refreshUnread = () => api.unread().then((result) => setUnread(result.count)).catch(() => {});
    window.addEventListener('peergrab:notification-read', refreshUnread);
    window.addEventListener('peergrab:wallet-changed', refreshBalance);
    const polling = window.setInterval(() => {
      if (!isWsAvailable()) refreshUnread();
    }, 5000);
    return () => {
      unsubscribe();
      window.removeEventListener('peergrab:notification-read', refreshUnread);
      window.removeEventListener('peergrab:wallet-changed', refreshBalance);
      window.clearInterval(polling);
    };
  }, [userId, route]);

  useEffect(() => { setMenuOpen(false); }, [route]);
  useEffect(() => {
    const closeOnEscape = (event: KeyboardEvent) => { if (event.key === 'Escape') setMenuOpen(false); };
    window.addEventListener('keydown', closeOnEscape);
    return () => window.removeEventListener('keydown', closeOnEscape);
  }, []);

  const current = IDENTITIES.find((identity) => identity.userId === userId);
  const activeHref = route.startsWith('/detail/') || route === '/' ? '/square' : route;
  const activeSection = route.startsWith('/detail/')
    ? '任务详情'
    : navigation.find((item) => item.href === activeHref)?.label ?? '任务广场';

  const renderNav = (group: 'task' | 'account') => navigation.filter((item) => item.group === group).map((item) => {
    const active = item.href === activeHref;
    return (
      <a key={item.href} className={`nav-item${active ? ' active' : ''}`} href={`#${item.href}`}
         aria-current={active ? 'page' : undefined} onClick={() => setMenuOpen(false)}>
        <span className="nav-icon"><UiIcon name={item.icon} size={19} /></span>
        <span className="nav-label">{item.label}</span>
        {item.href === '/notifications' && unread > 0 && <span className="nav-badge">{unread > 99 ? '99+' : unread}</span>}
        {active && item.href !== '/notifications' && <span className="nav-current-dot" />}
      </a>
    );
  });

  return (
    <>
      {menuOpen && <button className="sidebar-backdrop" aria-label="关闭导航" onClick={() => setMenuOpen(false)} />}
      <aside className={`sidebar${menuOpen ? ' sidebar-open' : ''}`} aria-label="主导航">
        <a className="sidebar-brand" href="#/square" onClick={() => setMenuOpen(false)}>
          <span className="brand-mark"><UiIcon name="zap" size={21} /></span>
          <span className="brand-copy"><strong>PeerGrab</strong><small>校园跑腿服务</small></span>
        </a>

        <nav className="sidebar-nav" aria-label="功能导航">
          <div className="sidebar-section-label">工作台</div>
          {renderNav('task')}
          <div className="sidebar-section-label section-label-spaced">我的账户</div>
          {renderNav('account')}
        </nav>

        <div className="sidebar-bottom">
          <div className="sidebar-guide">
            <span className="guide-icon"><UiIcon name="sparkles" size={18} /></span>
            <strong>从一件小事开始</strong>
            <p>发出需求，校园里的伙伴会及时响应。</p>
            <a href="#/publish" onClick={() => setMenuOpen(false)}>发布一个任务 <UiIcon name="arrowRight" size={14} /></a>
          </div>
          <div className="sidebar-profile">
            <span className="profile-avatar">{current?.initials ?? '我'}</span>
            <span className="profile-copy"><strong>{current?.name ?? `用户 ${userId}`}</strong><small>{current?.shortName ?? '演示用户'} · 在线</small></span>
            <button className="profile-logout" onClick={onLogout} aria-label="退出登录" title="退出登录">
              <UiIcon name="logOut" size={18} />
            </button>
          </div>
        </div>
      </aside>

      <header className="workspace-topbar">
        <div className="workspace-location">
          <button className="mobile-menu-btn" onClick={() => setMenuOpen(true)} aria-label="打开导航"
                  aria-expanded={menuOpen}><UiIcon name="menu" size={21} /></button>
          <span className="workspace-crumb-root">工作台</span><UiIcon name="chevronRight" size={15} />
          <strong className="workspace-crumb-current">{activeSection}</strong>
        </div>
        <div className="workspace-tools">
          <span className="environment-pill"><span /> 本地演示</span>
          <a className="topbar-balance" href="#/wallet" title="查看我的钱包">
            <UiIcon name="wallet" size={17} /> {balance === null ? '余额加载中' : yuan(balance)}
          </a>
          <a className="topbar-notice" href="#/notifications" aria-label={`消息通知${unread ? `，${unread}条未读` : ''}`}>
            <UiIcon name="bell" size={19} />{unread > 0 && <span className="notice-dot" />}
          </a>
          <span className="topbar-avatar" title={current?.name}>{current?.initials ?? '我'}</span>
        </div>
      </header>
    </>
  );
}
