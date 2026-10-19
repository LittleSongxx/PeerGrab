// WebSocket 客户端：连接、断线重连、心跳、事件分发、降级回退。
//
// 设计要点：
//   1. 连不上 / 断开时自动退回轮询模式（页面交互不中断）——
//      WS 是体验优化，不是可用性依赖
//   2. 重连用指数退避（1s -> 8s 封顶）+ 随机抖动，避免服务端故障时重连风暴
//   3. 心跳 ping/pong 防中间设备掐断空闲连接
//   4. 降级状态对外广播，页面可以明确告诉用户"实时已降级，正在自动刷新"
//
// 生命周期细节：所有事件处理器都绑定到"产生它的那个 socket 实例"，
// 旧连接迟到的 onclose/onerror 不会把新连接置空或清掉新心跳。

import { getToken, refreshSession } from "./api";

export interface WsEvent {
  type: "errand.status" | "notification.new" | "credit.changed";
  payload: Record<string, unknown>;
}

type Handler = (event: WsEvent) => void;
type AvailabilityListener = (available: boolean) => void;

const handlers = new Set<Handler>();
const availabilityListeners = new Set<AvailabilityListener>();
let socket: WebSocket | null = null;
let reconnectDelay = 1000;
let reconnectTimer: number | undefined;
let heartbeatTimer: number | undefined;
let wsAvailable = true; // false = 已降级为轮询模式
let wantsConnection = false;
let authRecoveryInFlight = false;

/** 订阅实时连接可用性；订阅时会立即回调一次当前状态。 */
export function subscribeWsAvailability(listener: AvailabilityListener): () => void {
  availabilityListeners.add(listener);
  listener(wsAvailable);
  return () => availabilityListeners.delete(listener);
}

function setWsAvailable(next: boolean) {
  if (wsAvailable === next) return;
  wsAvailable = next;
  availabilityListeners.forEach((listener) => listener(next));
}

export function subscribeWs(handler: Handler): () => void {
  handlers.add(handler);
  return () => handlers.delete(handler);
}

/** 服务端因鉴权拒绝关闭连接：1008 或 4xxx 业务关闭码 */
function isAuthCloseCode(code: number) {
  return code === 1008 || (code >= 4000 && code < 5000);
}

function clearHeartbeat() {
  if (heartbeatTimer !== undefined) {
    window.clearInterval(heartbeatTimer);
    heartbeatTimer = undefined;
  }
}

/** 指数退避重连 + 0~250ms 抖动；连续失败到上限就标记降级（页面转轮询），但仍继续尝试 */
function scheduleReconnect() {
  if (reconnectTimer !== undefined) return;
  const jitter = Math.floor(Math.random() * 250);
  reconnectTimer = window.setTimeout(() => {
    reconnectTimer = undefined;
    reconnectDelay = Math.min(reconnectDelay * 2, 8000);
    if (reconnectDelay >= 8000) setWsAvailable(false);
    connectWs();
  }, reconnectDelay + jitter);
}

/**
 * 鉴权关闭后先用 refresh token 换新 token；刷新失败也保持退避重连，
 * 而不是永久放弃——API 侧刷新成功、网络恢复后仍能自动恢复实时通道。
 */
async function reconnectAfterAuthClose() {
  if (authRecoveryInFlight) return;
  authRecoveryInFlight = true;
  try {
    const refreshed = await refreshSession();
    if (refreshed) reconnectDelay = 1000;
  } finally {
    authRecoveryInFlight = false;
  }
  if (!wantsConnection) return;
  scheduleReconnect();
}

if (typeof window !== "undefined") {
  // API 侧刷新成功后主动重连，避免"API 已恢复、WS 仍降级"。
  window.addEventListener("peergrab:auth-refreshed", () => {
    if (!wantsConnection || socket) return;
    reconnectDelay = 1000;
    connectWs();
  });
}

export function connectWs() {
  wantsConnection = true;
  const token = getToken();
  if (!token) return;
  if (socket && (socket.readyState === WebSocket.CONNECTING || socket.readyState === WebSocket.OPEN)) return;

  // 开发期由 Vite 代理，容器部署由 Nginx 代理到后端。
  const proto = location.protocol === "https:" ? "wss" : "ws";
  const url = `${proto}://${location.host}/ws?token=${encodeURIComponent(token)}`;

  let ws: WebSocket;
  try {
    ws = new WebSocket(url);
  } catch {
    setWsAvailable(false);
    scheduleReconnect();
    return;
  }
  socket = ws;

  ws.onopen = () => {
    if (ws !== socket) return;
    setWsAvailable(true);
    reconnectDelay = 1000; // 连上了，重置退避
    clearHeartbeat();
    // 心跳：30s 一次 ping
    heartbeatTimer = window.setInterval(() => {
      if (ws.readyState === WebSocket.OPEN) {
        ws.send("ping");
      }
    }, 30000);
  };

  ws.onmessage = (msg) => {
    if (ws !== socket) return;
    if (msg.data === "pong") return;
    try {
      const event = JSON.parse(msg.data) as WsEvent;
      handlers.forEach((handler) => handler(event));
    } catch {
      // 非 JSON 消息忽略
    }
  };

  ws.onclose = (event) => {
    if (ws !== socket) return; // 旧连接迟到的 close，不能影响新连接
    clearHeartbeat();
    socket = null;
    if (!wantsConnection) return;
    // 鉴权被拒：先刷新会话，成功则用新 token 继续按退避重连
    if (isAuthCloseCode(event.code)) {
      void reconnectAfterAuthClose();
      return;
    }
    scheduleReconnect();
  };

  ws.onerror = () => {
    if (ws !== socket) return;
    ws.close();
  };
}

export function disconnectWs() {
  wantsConnection = false;
  authRecoveryInFlight = false;
  if (reconnectTimer !== undefined) {
    window.clearTimeout(reconnectTimer);
    reconnectTimer = undefined;
  }
  clearHeartbeat();
  const current = socket;
  socket = null;
  current?.close();
}
