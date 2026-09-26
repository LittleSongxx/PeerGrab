import { useEffect, useState, type FormEvent } from 'react';
import { useHashRoute } from './router';
import { getUserId, login, logout } from './api';
import { IDENTITIES, demoPasswordFor } from './identity';
import UiIcon from './components/UiIcon';
import TopBar from './components/TopBar';
import Square from './pages/Square';
import Detail from './pages/Detail';
import Publish from './pages/Publish';
import Mine from './pages/Mine';
import Wallet from './pages/Wallet';
import Notifications from './pages/Notifications';
import Credit from './pages/Credit';
import { connectWs, disconnectWs } from './ws';

export { IDENTITIES } from './identity';

export default function App() {
  const route = useHashRoute();
  const [userId, setUserId] = useState<number | null>(getUserId());
  const [selectedId, setSelectedId] = useState<number>(() => getUserId() ?? 1001);
  const [password, setPassword] = useState(() => demoPasswordFor(getUserId() ?? 1001));
  const [showPassword, setShowPassword] = useState(false);
  const [loginError, setLoginError] = useState<string | null>(null);
  const [loggingIn, setLoggingIn] = useState(false);

  const submitLogin = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!password || loggingIn) return;
    setLoggingIn(true);
    setLoginError(null);
    try {
      await login(selectedId, password);
      setPassword('');
      setUserId(selectedId);
    } catch (error) {
      setLoginError(error instanceof Error ? error.message : '登录失败，请稍后重试');
    } finally {
      setLoggingIn(false);
    }
  };

  const signOut = async () => {
    disconnectWs();
    await logout();
    setUserId(null);
    setPassword(demoPasswordFor(selectedId));
    setLoginError(null);
  };

  useEffect(() => {
    if (userId !== null) {
      connectWs();
      return () => disconnectWs();
    }
  }, [userId]);

  useEffect(() => {
    const syncAuth = () => setUserId(getUserId());
    window.addEventListener('peergrab:auth-changed', syncAuth);
    return () => window.removeEventListener('peergrab:auth-changed', syncAuth);
  }, []);

  useEffect(() => {
    if (userId === null) setPassword(demoPasswordFor(selectedId));
  }, [userId, selectedId]);

  useEffect(() => {
    document.getElementById('main-content')?.scrollTo({ top: 0, behavior: 'auto' });
  }, [route]);

  if (userId === null) {
    return (
      <div className="login-page">
        <div className="login-layout">
          <section className="login-story" aria-label="PeerGrab 介绍">
            <div className="login-brand">
              <span className="brand-mark"><UiIcon name="zap" size={22} /></span>
              <div><strong>PeerGrab</strong><span>校园跑腿抢单市场</span></div>
            </div>
            <div className="login-story-content">
              <span className="story-kicker"><UiIcon name="sparkles" size={15} /> 校园生活，即刻响应</span>
              <h1>让每一件小事，<br /><em>都有人及时响应。</em></h1>
              <p>发布需求、找到伙伴、安心结算。让校园里值得信任的帮助，来得更快一些。</p>
              <div className="story-benefits">
                <div><span className="story-benefit-icon"><UiIcon name="zap" size={18} /></span><strong>快速匹配</strong><small>多个伙伴实时抢单</small></div>
                <div><span className="story-benefit-icon"><UiIcon name="shield" size={18} /></span><strong>交易安心</strong><small>悬赏资金先行托管</small></div>
                <div><span className="story-benefit-icon"><UiIcon name="activity" size={18} /></span><strong>进度清晰</strong><small>每一步都有记录</small></div>
              </div>
            </div>
            <div className="story-footer"><span className="story-online-dot" /> 在线演示环境已就绪 <span>·</span> 真实业务流程体验</div>
          </section>

          <section className="login-form-side">
            <div className="login-card">
              <div className="login-card-heading">
                <span className="eyebrow">欢迎使用</span>
                <h2>选择身份，进入工作台</h2>
                <p>四种演示身份对应完整的跑腿交易流程。</p>
              </div>
              <form onSubmit={submitLogin}>
                <fieldset className="role-fieldset">
                  <legend>体验身份</legend>
                  <div className="role-grid">
                    {IDENTITIES.map((identity) => (
                      <button key={identity.userId} type="button"
                              className={`role-option${selectedId === identity.userId ? ' is-selected' : ''}`}
                              aria-pressed={selectedId === identity.userId}
                              onClick={() => { setSelectedId(identity.userId); setPassword(demoPasswordFor(identity.userId)); setLoginError(null); }}>
                        <span className={`role-avatar role-avatar-${identity.userId}`}>{identity.initials}</span>
                        <span className="role-option-copy"><strong>{identity.name}</strong><small>{identity.desc}</small></span>
                        <span className="role-option-check"><UiIcon name="check" size={14} /></span>
                      </button>
                    ))}
                  </div>
                </fieldset>

                <div className="login-password-field">
                  <label htmlFor="demo-password">演示密码</label>
                  <div className="password-input-wrap">
                    <UiIcon name="lock" size={18} />
                    <input id="demo-password" type={showPassword ? 'text' : 'password'}
                           value={password} autoComplete="current-password" placeholder="当前身份的演示密码"
                           onChange={(event) => setPassword(event.target.value)} />
                    <button type="button" className="password-visibility"
                            onClick={() => setShowPassword((value) => !value)}
                            aria-label={showPassword ? '隐藏密码' : '显示密码'}>
                      {showPassword ? '隐藏' : '显示'}
                    </button>
                  </div>
                </div>
                {loginError && <div className="banner warn" role="alert">{loginError}</div>}
                <button type="submit" className="btn btn-primary login-submit" disabled={loggingIn || !password}>
                  {loggingIn ? '正在进入…' : '进入工作台'} <UiIcon name="arrowRight" size={18} />
                </button>
              </form>
              <div className="login-hint"><UiIcon name="shield" size={15} /> 密码已按身份自动填写；所有金额均为演示数据。</div>
            </div>
          </section>
        </div>
      </div>
    );
  }

  let page;
  if (route.startsWith('/detail/')) {
    page = <Detail errandId={route.split('/')[2]} onIdentityChange={setUserId} />;
  } else if (route === '/publish') {
    page = <Publish />;
  } else if (route === '/mine') {
    page = <Mine />;
  } else if (route === '/wallet') {
    page = <Wallet />;
  } else if (route === '/notifications') {
    page = <Notifications />;
  } else if (route === '/credit') {
    page = <Credit />;
  } else {
    page = <Square />;
  }

  return (
    <div className="app-shell">
      <TopBar route={route} userId={userId} onLogout={signOut} />
      <main className="main" id="main-content"><div className="page-container">{page}</div></main>
    </div>
  );
}
