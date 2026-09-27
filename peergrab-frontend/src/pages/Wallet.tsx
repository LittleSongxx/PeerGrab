import { useCallback, useEffect, useRef, useState } from 'react';
import { api, yuan } from '../api';

const REF_TYPE_TEXT: Record<string, string> = {
  ESCROW: '发布托管', SETTLE: '结算', REFUND: '退款',
  RECHARGE: '充值', WITHDRAW: '提现',
};

export default function Wallet() {
  const [balance, setBalance] = useState<{ availableCents: number; frozenCents: number } | null>(null);
  const [ledger, setLedger] = useState<Awaited<ReturnType<typeof api.ledger>>>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [nextCursor, setNextCursor] = useState('');
  const [loadingMore, setLoadingMore] = useState(false);
  const [moreError, setMoreError] = useState<string | null>(null);
  const requestVersion = useRef(0);

  const load = useCallback(async () => {
    const version = ++requestVersion.current;
    setLoading(true);
    setLoadingMore(false);
    setError(null);
    setMoreError(null);
    try {
      const [nextBalance, page] = await Promise.all([api.wallet(), api.ledgerByCursor()]);
      if (version === requestVersion.current) {
        setBalance(nextBalance);
        setLedger(page.items);
        setNextCursor(page.nextCursor);
      }
    } catch (cause) {
      if (version === requestVersion.current) {
        setError(cause instanceof Error ? cause.message : '钱包加载失败，请稍后重试');
      }
    } finally {
      if (version === requestVersion.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
    return () => { requestVersion.current += 1; };
  }, [load]);

  const loadMore = async () => {
    if (!nextCursor || loading || loadingMore) return;
    const version = requestVersion.current;
    const cursor = nextCursor;
    setLoadingMore(true);
    setMoreError(null);
    try {
      const page = await api.ledgerByCursor(cursor);
      if (version !== requestVersion.current) return;
      setLedger((current) => {
        const seen = new Set(current.map((entry) => entry.id));
        return [...current, ...page.items.filter((entry) => !seen.has(entry.id))];
      });
      setNextCursor(page.nextCursor === cursor ? '' : page.nextCursor);
    } catch (cause) {
      if (version === requestVersion.current) {
        setMoreError(cause instanceof Error ? cause.message : '后续流水加载失败，请重试');
      }
    } finally {
      if (version === requestVersion.current) setLoadingMore(false);
    }
  };

  return (
    <div className="wallet-page page-stack">
      <header className="page-intro">
        <div>
          <span className="page-eyebrow">收支概览</span>
          <h1>我的钱包</h1>
          <p>查看可用余额、冻结余额和每笔任务资金流转。</p>
        </div>
        <button className="btn btn-secondary page-intro-action" type="button" onClick={() => void load()} disabled={loading}>
          {loading ? '刷新中…' : '刷新数据'}
        </button>
      </header>

      {error && (
        <div className="banner warn wallet-error" role="alert">
          {error} <button className="link-btn" type="button" onClick={() => void load()}>重试</button>
        </div>
      )}

      <section className="wallet-overview" aria-label="账户余额">
        <div className="wallet-balance-primary">
          <span className="wallet-balance-label">可用余额</span>
          <strong>{balance ? yuan(balance.availableCents) : '—'}</strong>
          <span className="wallet-balance-caption">可用于发布并托管新任务</span>
        </div>
        <div className="wallet-balance-secondary section-card">
          <span className="wallet-balance-label">冻结资金</span>
          <strong>{balance ? yuan(balance.frozenCents) : '—'}</strong>
          <span className="wallet-balance-caption">账户内冻结余额，不含已转入平台托管的悬赏</span>
        </div>
      </section>

      <section className="section-card wallet-ledger" aria-labelledby="wallet-ledger-title">
        <div className="section-heading">
          <div>
            <span className="section-kicker">交易记录</span>
            <h2 id="wallet-ledger-title">资金流水</h2>
            <p>每笔资金动作分别记录转出和入账，包括平台托管账户。</p>
          </div>
          {!loading && !error && <span className="section-count">已显示 {ledger.length} 笔记录</span>}
        </div>

        {loading && !balance && <div className="loading-state" role="status">正在加载钱包…</div>}
        {!loading && !error && ledger.length === 0 && (
          <div className="empty-state wallet-empty">
            <span className="empty-state-icon" aria-hidden="true">◎</span>
            <h3>还没有交易记录</h3>
            <p>发布任务或完成跑腿后，流水会显示在这里。</p>
          </div>
        )}
        {ledger.length > 0 && (
          <div className="table-scroll">
            <table className="ledger-table">
              <thead>
                <tr><th scope="col">时间</th><th scope="col">方向</th><th scope="col">金额</th><th scope="col">类型</th><th scope="col">关联任务</th></tr>
              </thead>
              <tbody>
                {ledger.map((entry) => {
                  const incoming = entry.direction === 'CREDIT';
                  const hasErrand = ['ESCROW', 'SETTLE', 'REFUND'].includes(entry.refType);
                  return (
                    <tr key={entry.id}>
                      <td className="wallet-ledger-time">{new Date(entry.time).toLocaleString('zh-CN')}</td>
                      <td><span className={incoming ? 'wallet-direction is-credit' : 'wallet-direction is-debit'}>{incoming ? '入账' : '转出'}</span></td>
                      <td className={incoming ? 'wallet-amount credit' : 'wallet-amount debit'}>
                        {incoming ? '+' : '-'}{yuan(entry.amountCents)}
                      </td>
                      <td>{REF_TYPE_TEXT[entry.refType] ?? entry.refType}</td>
                      <td>{hasErrand ? <a href={`#/detail/${entry.refId}`}>#{String(entry.refId).slice(-8)}</a> : <span className="muted">—</span>}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
        {(nextCursor || moreError) && !loading && (
          <div className="mine-load-more">
            {moreError && <p className="banner warn" role="alert">{moreError}</p>}
            <button className="btn btn-secondary" type="button" disabled={loadingMore}
                    onClick={() => void loadMore()}>
              {loadingMore ? '加载中…' : moreError ? '重试加载' : '加载更多流水'}
            </button>
          </div>
        )}
      </section>
    </div>
  );
}
