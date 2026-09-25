import { useCallback, useEffect, useState } from 'react';
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

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [nextBalance, nextLedger] = await Promise.all([api.wallet(), api.ledger()]);
      setBalance(nextBalance);
      setLedger(nextLedger);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '钱包加载失败，请稍后重试');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

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
          {!loading && !error && <span className="section-count">{ledger.length} 笔记录</span>}
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
                    <tr key={entry.bizNo + entry.direction}>
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
      </section>
    </div>
  );
}
