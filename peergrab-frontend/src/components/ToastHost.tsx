import { useEffect, useState } from "react";
import { TOAST_EVENT_NAME } from "../utils/toast";
import type { ToastDetail } from "../utils/toast";

const MAX_VISIBLE = 3;
const DISMISS_MS = 4500;

/** 全局提示容器：监听 peergrab:toast 事件，最多同时显示 3 条，4.5 秒后自动消失。 */
export default function ToastHost() {
  const [items, setItems] = useState<ToastDetail[]>([]);

  useEffect(() => {
    const onToast = (event: Event) => {
      const detail = (event as CustomEvent<ToastDetail>).detail;
      if (!detail) return;
      setItems((current) => {
        const next = current.filter((item) => item.id !== detail.id).concat(detail);
        return next.slice(next.length - MAX_VISIBLE);
      });
      window.setTimeout(() => {
        setItems((current) => current.filter((item) => item.id !== detail.id));
      }, DISMISS_MS);
    };
    window.addEventListener(TOAST_EVENT_NAME, onToast);
    return () => window.removeEventListener(TOAST_EVENT_NAME, onToast);
  }, []);

  if (items.length === 0) return null;

  return (
    <div className="toast-host" aria-live="polite" data-testid="toast-host">
      {items.map((item) => (
        <div
          key={item.id}
          className={`toast toast-${item.kind}`}
          role={item.kind === "error" ? "alert" : "status"}
          data-testid="toast"
        >
          <span className="toast-message">{item.text}</span>
          <button
            type="button"
            className="toast-close"
            aria-label="关闭提示"
            onClick={() => setItems((current) => current.filter((entry) => entry.id !== item.id))}
          >
            ×
          </button>
        </div>
      ))}
    </div>
  );
}
