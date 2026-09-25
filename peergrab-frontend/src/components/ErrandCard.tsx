import { ErrandCard as Card, STATUS_TEXT, yuan } from '../api';
import ActionButtons from './ActionButtons';

interface Props {
  card: Card;
  onChanged: () => void;
}

const TYPE_TEXT: Record<string, string> = {
  DELIVERY: '代取代送', BUY: '代买', QUEUE: '代排队', OTHER: '其他任务',
};

export default function ErrandCardView({ card, onChanged }: Props) {
  return (
    <article className="errand-card">
      <div className="errand-card__top">
        <span className="errand-card__type">{TYPE_TEXT[card.type] ?? card.type}</span>
        <span className={`status status-${card.status}`}>{STATUS_TEXT[card.status] ?? card.status}</span>
      </div>
      <h3 className="errand-card__title">
        <a href={`#/detail/${card.id}`}>{card.title}</a>
      </h3>
      <div className="errand-card__body">
        <div className="errand-card__reward">
          <span>悬赏报酬</span>
          <strong className="reward">{yuan(card.rewardCents)}</strong>
          <small>发布时托管</small>
        </div>
        <div className="errand-card__capacity">
          <span>接单名额</span>
          <strong>{card.slotTaken} / {card.slotTotal}</strong>
          <small>已接 / 总名额</small>
        </div>
      </div>
      <div className="errand-card__meta">
        <span>发单人 #{String(card.publisherId).slice(-6)}</span>
        {card.round > 0 && <span className="round">第 {card.round + 1} 轮</span>}
        <span className="errand-card__id">任务 #{String(card.id).slice(-8)}</span>
      </div>
      <footer className="errand-card__footer">
        <ActionButtons card={card} onChanged={onChanged} compact />
        <a className="errand-card__link" href={`#/detail/${card.id}`}>查看详情 <span aria-hidden="true">↗</span></a>
      </footer>
    </article>
  );
}
