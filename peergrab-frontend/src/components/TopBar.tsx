import { useEffect, useRef, useState } from "react";
import { api } from "../api";
import type { UserRole } from "../api";
import { subscribeWs, subscribeWsAvailability } from "../ws";
import { IDENTITIES } from "../identity";
import { formatCents } from "../utils/money";
import type { Cents } from "../utils/money";
import UiIcon, { type UiIconName } from "./UiIcon";

interface Props {
  route: string;
  userId: number;
  onLogout: () => void;
  role: UserRole | null;
}

const navigation: { href: string; label: string; icon: UiIconName; group: "task" | "account" }[] = [
  { href: "/square", label: "任务广场", icon: "grid", group: "task" },
  { href: "/publish", label: "发布任务", icon: "plus", group: "task" },
  { href: "/mine", label: "我的任务", icon: "tasks", group: "task" },
  { href: "/wallet", label: "我的钱包", icon: "wallet", group: "account" },
  { href: "/credit", label: "信用中心", icon: "award", group: "account" },
  { href: "/notifications", label: "消息通知", icon: "bell", group: "account" },
];

export default function TopBar({ route, userId, onLogout, role }: Props) {
  const [unread, setUnread] = useState(0);
  const [balance, setBalance] = useState<Cents | null>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  const [wsAvailable, setWsAvailable] = useState(true);
  const menuButtonRef = useRef<HTMLButtonElement | null>(null);
  const sidebarRef = useRef<HTMLElement | null>(null);

  // 仲裁员不参与发布/抢单：隐藏发布入口，按钮集合仍由后端 availableActions 决定
  const isArbitrator = role === "ARBITRATOR";
  const navItems = isArbitrator ? navigation.filter((item) => item.href !== "/publish") : navigation;

  useEffect(() => {
    api.unread().then((result) => setUnread(result.count)).catch(() => {});
    const refreshBalance = () => {
      api.wallet().then((wallet) => setBalance(wallet.availableCents)).catch(() => {});
    };
    refreshBalance();

    const refreshUnread = () => api.unread().then((result) => setUnread(result.count)).catch(() => {});
    const unsubscribeWs = subscribeWs((event) => {
      if (event.type === "notification.new") {
        refreshUnread();
        refreshBalance();
      }
    });
    const unsubscribeAvailability = subscribeWsAvailability(setWsAvailable);
    window.addEventListener("peergrab:notification-read", refreshUnread);
    window.addEventListener("peergrab:wallet-changed", refreshBalance);
    // Reconcile even while WS is connected: another app replica may have consumed
    // the event, and WebSocket delivery is deliberately best-effort.
    const polling = window.setInterval(() => {
      refreshUnread();
      refreshBalance();
    }, 30000);
    return () => {
      unsubscribeWs();
      unsubscribeAvailability();
      window.removeEventListener("peergrab:notification-read", refreshUnread);
      window.removeEventListener("peergrab:wallet-changed", refreshBalance);
      window.clearInterval(polling);
    };
  }, [userId]);

  useEffect(() => {
    setMenuOpen(false);
  }, [route]);

  const closeMenu = () => {
    setMenuOpen(false);
    window.setTimeout(() => menuButtonRef.current?.focus(), 0);
  };

  useEffect(() => {
    if (!menuOpen) return;
    const sidebar = sidebarRef.current;
    const focusables = () =>
      Array.from(sidebar?.querySelectorAll<HTMLElement>('a[href], button:not([disabled])') ?? []);
    focusables()[0]?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        closeMenu();
        return;
      }
      if (event.key !== "Tab") return;
      const items = focusables();
      if (items.length === 0) return;
      const first = items[0];
      const last = items[items.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [menuOpen]);

  const current = IDENTITIES.find((identity) => identity.userId === userId);
  const activeHref = route.startsWith("/detail/") || route === "/" ? "/square" : route;
  const activeSection = route.startsWith("/detail/")
    ? "任务详情"
    : (navItems.find((item) => item.href === activeHref) ?? navigation.find((item) => item.href === activeHref))?.label
      ?? "任务广场";

  const renderNav = (group: "task" | "account") =>
    navItems
      .filter((item) => item.group === group)
      .map((item) => {
        const active = item.href === activeHref;
        return (
          <a
            key={item.href}
            className={`nav-item${active ? " active" : ""}`}
            href={`#${item.href}`}
            aria-current={active ? "page" : undefined}
            data-testid={`nav-${item.href.replace("/", "")}`}
            onClick={() => closeMenu()}
          >
            <span className="nav-icon"><UiIcon name={item.icon} size={19} /></span>
            <span className="nav-label">{item.label}</span>
            {item.href === "/notifications" && unread > 0 && (
              <span className="nav-badge">{unread > 99 ? "99+" : unread}</span>
            )}
            {active && item.href !== "/notifications" && <span className="nav-current-dot" />}
          </a>
        );
      });

  return (
    <>
      {menuOpen && <button className="sidebar-backdrop" aria-label="关闭导航" onClick={closeMenu} data-testid="sidebar-backdrop" />}
      <aside
        className={`sidebar${menuOpen ? " sidebar-open" : ""}`}
        aria-label="主导航"
        ref={sidebarRef}
        data-testid="sidebar"
      >
        <button type="button" className="sidebar-close-btn" aria-label="关闭导航" onClick={closeMenu}>
          <UiIcon name="close" size={18} />
        </button>
        <a className="sidebar-brand" href="#/square" onClick={() => closeMenu()}>
          <span className="brand-mark"><UiIcon name="zap" size={21} /></span>
          <span className="brand-copy"><strong>PeerGrab</strong><small>校园跑腿服务</small></span>
        </a>

        <nav className="sidebar-nav" aria-label="功能导航">
          <div className="sidebar-section-label">工作台</div>
          {renderNav("task")}
          <div className="sidebar-section-label section-label-spaced">我的账户</div>
          {renderNav("account")}
        </nav>

        <div className="sidebar-bottom">
          <div className="sidebar-guide">
            <span className="guide-icon"><UiIcon name={isArbitrator ? "shield" : "sparkles"} size={18} /></span>
            {isArbitrator ? (
              <>
                <strong>仲裁工作台</strong>
                <p>在争议任务里核对事实，决定托管资金的去向。</p>
              </>
            ) : (
              <>
                <strong>从一件小事开始</strong>
                <p>发出需求，校园里的伙伴会及时响应。</p>
                <a href="#/publish" onClick={() => closeMenu()}>发布一个任务 <UiIcon name="arrowRight" size={14} /></a>
              </>
            )}
          </div>
          <div className="sidebar-profile">
            <span className="profile-avatar">{current?.initials ?? "我"}</span>
            <span className="profile-copy"><strong>{current?.name ?? `用户 ${userId}`}</strong><small>{current?.shortName ?? "演示用户"} · 在线</small></span>
            <button className="profile-logout" onClick={onLogout} aria-label="退出登录" title="退出登录">
              <UiIcon name="logOut" size={18} />
            </button>
          </div>
        </div>
      </aside>

      <header className="workspace-topbar">
        <div className="workspace-location">
          <button
            ref={menuButtonRef}
            className="mobile-menu-btn"
            onClick={() => setMenuOpen(true)}
            aria-label="打开导航"
            aria-expanded={menuOpen}
            data-testid="nav-menu-button"
          >
            <UiIcon name="menu" size={21} />
          </button>
          <span className="workspace-crumb-root">工作台</span><UiIcon name="chevronRight" size={15} />
          <strong className="workspace-crumb-current">{activeSection}</strong>
        </div>
        <div className="workspace-tools">
          {!wsAvailable && (
            <span className="ws-degraded-pill" title="实时连接不可用，页面会定时刷新" data-testid="ws-degraded">
              实时已降级
            </span>
          )}
          <span className="environment-pill"><span /> 在线演示</span>
          <a className="topbar-balance" href="#/wallet" title="查看我的钱包">
            <UiIcon name="wallet" size={17} /> {balance === null ? "余额加载中" : formatCents(balance)}
          </a>
          <a className="topbar-notice" href="#/notifications" aria-label={`消息通知${unread ? `，${unread}条未读` : ""}`}>
            <UiIcon name="bell" size={19} />{unread > 0 && <span className="notice-dot" />}
          </a>
          <span className="topbar-avatar" title={current?.name}>{current?.initials ?? "我"}</span>
        </div>
      </header>
    </>
  );
}
