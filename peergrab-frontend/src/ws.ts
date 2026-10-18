// WebSocket 客户端：连接、断线重连、心跳、事件分发、降级回退。
//
// 设计要点：
//   1. 连不上 / 断开时自动退回轮询模式（页面交互不中断）——
//      WS 是体验优化，不是可用性依赖
//   2. 重连用指数退避（1s -> 2s -> 4s -> 8s 封顶），避免服务端故障时重连风暴
//   3. 心跳 ping/pong 防中间设备掐断空闲连接

import { getToken, refreshSession } from './api';

export interface WsEvent {
  type: 'errand.status' | 'notification.new' | 'credit.changed';
  payload: Record<string, unknown>;
}

type Handler = (event: WsEvent) => void;

const handlers = new Set<Handler>();
let socket: WebSocket | null = null;
let reconnectDelay = 1000;
let heartbeatTimer: number | undefined;
let reconnectTimer: number | undefined;
let wsAvailable = true; // false = 已降级为轮询模式
let wantsConnection = false;

export function isWsAvailable() {
  return wsAvailable;
}

export function subscribeWs(handler: Handler): () => void {
  handlers.add(handler);
  return () => handlers.delete(handler);
}

/** 服务端因鉴权拒绝关闭连接：1008 或 4xxx 业务关闭码 */
function isAuthCloseCode(code: number) {
  return code === 1008 || (code >= 4000 && code < 5000);
}

/** 指数退避重连；连续失败到上限就标记降级（页面转轮询），但仍继续尝试 */
function scheduleReconnect() {
  reconnectTimer = window.setTimeout(() => {
    reconnectDelay = Math.min(reconnectDelay * 2, 8000);
    if (reconnectDelay >= 8000) wsAvailable = false;
    connectWs();
  }, reconnectDelay);
}

/**
 * 鉴权关闭后先用 refresh token 换新 token（connectWs 内的 getToken() 会读到新值）。
 * 刷新失败就停止自动重连，避免拿着过期 token 无限重连；
 * 下一次 connectWs()（重新登录 / API 401 刷新后）仍可再次尝试。
 */
async function reconnectAfterAuthClose() {
  const refreshed = await refreshSession();
  if (!wantsConnection) return;
  if (refreshed) scheduleReconnect();
  else wsAvailable = false;
}

export function connectWs() {
  wantsConnection = true;
  const token = getToken();
  if (!token) return;
  if (socket && socket.readyState !== WebSocket.CLOSED) return;

  // 开发期由 Vite 代理，容器部署由 Nginx 代理到后端。
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const url = `${proto}://${location.host}/ws?token=${encodeURIComponent(token)}`;

  try {
    socket = new WebSocket(url);
  } catch {
    wsAvailable = false;
    return;
  }

  socket.onopen = () => {
    wsAvailable = true;
    reconnectDelay = 1000; // 连上了，重置退避
    // 心跳：30s 一次 ping
    heartbeatTimer = window.setInterval(() => {
      if (socket?.readyState === WebSocket.OPEN) {
        socket.send('ping');
      }
    }, 30000);
  };

  socket.onmessage = (msg) => {
    if (msg.data === 'pong') return;
    try {
      const event = JSON.parse(msg.data) as WsEvent;
      handlers.forEach((h) => h(event));
    } catch {
      // 非 JSON 消息忽略
    }
  };

  socket.onclose = (event) => {
    if (heartbeatTimer) window.clearInterval(heartbeatTimer);
    socket = null;
    if (!wantsConnection) return;
    // 鉴权被拒：先刷新会话，成功则用新 token 继续按退避重连
    if (isAuthCloseCode(event.code)) {
      void reconnectAfterAuthClose();
      return;
    }
    scheduleReconnect();
  };

  socket.onerror = () => {
    socket?.close();
  };
}

export function disconnectWs() {
  wantsConnection = false;
  if (heartbeatTimer) window.clearInterval(heartbeatTimer);
  if (reconnectTimer) window.clearTimeout(reconnectTimer);
  socket?.close();
  socket = null;
}
