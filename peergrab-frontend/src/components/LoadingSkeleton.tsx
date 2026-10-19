interface Props {
  variant?: "cards" | "rows" | "table" | "panel";
  rows?: number;
  label?: string;
}

/**
 * 统一的加载骨架屏。文字 "正在加载…" 对布局没有稳定预期，
 * 骨架屏让内容区在首屏就保持接近最终布局，避免加载完成后的跳变。
 */
export default function LoadingSkeleton({ variant = "cards", rows = 3, label = "正在加载…" }: Props) {
  const items = Array.from({ length: rows }, (_, index) => index);
  return (
    <div
      className={`skeleton skeleton-${variant}`}
      role="status"
      aria-live="polite"
      aria-busy="true"
      data-testid="skeleton"
    >
      <span className="sr-only">{label}</span>
      {variant === "cards" &&
        items.map((index) => (
          <div key={index} className="skeleton-card" aria-hidden="true">
            <span className="skeleton-line skeleton-w-40" />
            <span className="skeleton-line skeleton-w-80" />
            <span className="skeleton-block" />
            <span className="skeleton-line skeleton-w-60" />
          </div>
        ))}
      {(variant === "rows" || variant === "table") &&
        items.map((index) => (
          <div key={index} className="skeleton-row" aria-hidden="true">
            <span className="skeleton-line skeleton-w-30" />
            <span className="skeleton-line skeleton-w-50" />
            <span className="skeleton-line skeleton-w-20" />
          </div>
        ))}
      {variant === "panel" && (
        <div className="skeleton-panel" aria-hidden="true">
          <span className="skeleton-line skeleton-w-40" />
          <span className="skeleton-block" />
          <span className="skeleton-line skeleton-w-80" />
          <span className="skeleton-line skeleton-w-60" />
        </div>
      )}
    </div>
  );
}
