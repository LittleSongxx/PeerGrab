/**
 * 全局轻量提示：任何业务动作都可以 emit 一条 toast，由 App 内的 ToastHost 统一渲染。
 *
 * 用自定义事件而不是引入状态库，是为了保持"无状态库"的现状：
 * 提示是瞬时 UI 反馈，不参与数据流，也不值得为它引入依赖。
 */

export type ToastKind = "success" | "info" | "error";

export interface ToastDetail {
  id: number;
  kind: ToastKind;
  text: string;
}

export const TOAST_EVENT_NAME = "peergrab:toast";

let sequence = 0;

export function showToast(kind: ToastKind, text: string): void {
  if (typeof window === "undefined" || !text) return;
  const detail: ToastDetail = { id: ++sequence, kind, text };
  window.dispatchEvent(new CustomEvent<ToastDetail>(TOAST_EVENT_NAME, { detail }));
}
