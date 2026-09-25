// 后端请求封装：token 管理 + Result 统一处理。
//
// 后端响应结构是 Result<T> = { code, message, data }：
//   code === 'OK' 表示成功；其他 code（SLOT_FULL / NOT_CURRENT_GRABBER 等）
//   是业务结果而不是服务器错误——前端按 code 分支处理，绝不解析文案。

export interface ApiResult<T> {
  code: string;
  message: string;
  data: T;
}

let token: string | null = localStorage.getItem('peergrab_token');
let currentUserId: number | null = JSON.parse(localStorage.getItem('peergrab_user') || 'null');
let refreshToken: string | null = localStorage.getItem('peergrab_refresh');
let refreshInFlight: Promise<boolean> | null = null;
let authGeneration = 0;

export interface DemoCredentials {
  token: string;
  refreshToken?: string;
  userId: number;
}

export function getToken() { return token; }
export function getUserId() { return currentUserId; }

/** Request a demo token without changing the current browser identity. */
export async function issueDemoCredentials(userId: number, password: string): Promise<DemoCredentials> {
  const resp = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ userId, password }),
  });
  const body = (await resp.json()) as ApiResult<DemoCredentials>;
  if (body.code !== 'OK') throw new Error(body.message);
  return body.data;
}

export async function login(userId: number, password: string): Promise<string> {
  const credentials = await issueDemoCredentials(userId, password);
  authGeneration++;
  token = credentials.token;
  refreshToken = credentials.refreshToken ?? null;
  currentUserId = userId;
  localStorage.setItem('peergrab_token', token!);
  localStorage.setItem('peergrab_user', JSON.stringify(userId));
  if (refreshToken) localStorage.setItem('peergrab_refresh', refreshToken);
  else localStorage.removeItem('peergrab_refresh');
  return token!;
}

function clearLocalAuth() {
  authGeneration++;
  token = null;
  refreshToken = null;
  currentUserId = null;
  localStorage.removeItem('peergrab_token');
  localStorage.removeItem('peergrab_refresh');
  localStorage.removeItem('peergrab_user');
  window.dispatchEvent(new Event('peergrab:auth-changed'));
}

async function renewSession(): Promise<boolean> {
  if (!refreshToken) return false;
  if (!refreshInFlight) {
    const oldRefreshToken = refreshToken;
    const generation = authGeneration;
    refreshInFlight = (async () => {
      try {
        const resp = await fetch('/api/auth/refresh', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken: oldRefreshToken }),
        });
        const body = (await resp.json()) as ApiResult<{ accessToken: string; refreshToken: string }>;
        if (body.code !== 'OK' || !body.data?.accessToken || !body.data?.refreshToken) return false;
        if (generation !== authGeneration) {
          await revokeCredentials({ token: body.data.accessToken, refreshToken: body.data.refreshToken });
          return false;
        }
        token = body.data.accessToken;
        refreshToken = body.data.refreshToken;
        localStorage.setItem('peergrab_token', token);
        localStorage.setItem('peergrab_refresh', refreshToken);
        return true;
      } catch {
        return false;
      }
    })().finally(() => { refreshInFlight = null; });
  }
  return refreshInFlight;
}

/** Invalidate both server credentials, then clear local state even when offline. */
export async function revokeCredentials(credentials: Pick<DemoCredentials, 'token' | 'refreshToken'>): Promise<void> {
  try {
    await fetch('/api/auth/logout', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${credentials.token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: credentials.refreshToken ?? null }),
    });
  } catch {
    // Local sign-out must still complete if the network is unavailable.
  }
}

export async function logout() {
  const credentials = token ? { token, refreshToken: refreshToken ?? undefined } : null;
  clearLocalAuth();
  if (credentials) await revokeCredentials(credentials);
}

export class BizError extends Error {
  code: string;
  constructor(code: string, message: string) {
    super(message);
    this.code = code;
  }
}

/** 发请求并返回 data；code 非 OK 时抛 BizError（带业务码） */
async function request<T>(path: string, method = 'GET', body?: unknown,
                          extraHeaders?: Record<string, string>): Promise<T> {
  const send = () => fetch(path, {
    method,
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...extraHeaders,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const requestToken = token;
  let resp = await send();
  if (resp.status === 401) {
    if (token && token !== requestToken) {
      resp = await send();
    } else if (await renewSession()) {
      resp = await send();
    }
  }
  if (resp.status === 401) {
    clearLocalAuth();
    location.hash = '#/';
    throw new BizError('UNAUTHORIZED', '会话已过期，请重新登录');
  }
  const result = (await resp.json()) as ApiResult<T>;
  if (result.code !== 'OK') throw new BizError(result.code, result.message);
  return result.data;
}

// ── 任务 ──────────────────────────────────────────────
export interface ErrandCard {
  /** 后端以字符串返回：雪花 ID 超过 JS 安全整数（2^53），
   *  按 number 解析会截断精度，全程必须按 string 处理 */
  id: string;
  title: string;
  status: string;
  type: string;
  rewardCents: number;
  slotTotal: number;
  slotTaken: number;
  publisherId: string;
  grabberId: string;
  round: number;
  role: string;
  availableActions: string[];
}

export interface ErrandCursorPage {
  items: ErrandCard[];
  nextCursor: string;
}

export interface StatusChange {
  time: string; from: string; to: string; round: number; operatorId: string;
}

export interface CreditSelf {
  score: number;
  windowDays: number;
  events: { type: string; description: string; delta: number; refId: string; time: string }[];
}

export interface CreditRankEntry {
  userId: string;
  score: number;
}

export const api = {
  health: () => request<{ status: string }>('/api/health'),
  list: (campusId = 1, status?: string) =>
    request<ErrandCard[]>(`/api/errands?campusId=${campusId}${status ? `&status=${status}` : ''}`),
  listByCursor: (campusId = 1, cursor = '', size = 20, status?: string) => {
    const params = new URLSearchParams({ campusId: String(campusId), cursor, size: String(size) });
    if (status) params.set('status', status);
    return request<ErrandCursorPage>(`/api/errands?${params.toString()}`);
  },
  mine: (role: 'PUBLISHED_BY_ME' | 'GRABBED_BY_ME', page = 0, size = 20) =>
    request<ErrandCard[]>(`/api/errands/mine?role=${role}&page=${page}&size=${size}`),
  detail: (id: string) => request<ErrandCard>(`/api/errands/${id}`),
  timeline: (id: string) => request<StatusChange[]>(`/api/errands/${id}/timeline`),
  publish: (req: { title: string; rewardCents: number; slotTotal: number; type?: string }, requestId: string) =>
    request<{ errandId: string; status: string }>('/api/errands', 'POST', req,
      { 'X-Request-Id': requestId }),
  grab: (id: string) =>
    request<{ code: string; grabbed: boolean }>(`/api/errands/${id}/grab`, 'POST'),
  action: (id: string, name: 'confirm' | 'pickup' | 'deliver' | 'settle' | 'cancel' | 'dispute') =>
    request<unknown>(`/api/errands/${id}/${name}`, 'POST'),
  arbitrate: (id: string, favor: 'RUNNER' | 'PUBLISHER') =>
    request<{ result: string }>(`/api/errands/${id}/arbitrate`, 'POST', { favor }),
  wallet: () => request<{ availableCents: number; frozenCents: number }>('/api/wallet'),
  ledger: () => request<{
    time: string; direction: string; amountCents: number;
    refType: string; refId: string; bizNo: string;
  }[]>('/api/wallet/ledger'),
  notifications: () => request<{
    id: string; errandId: string; type: string; content: string; time: string; read: boolean;
  }[]>('/api/notifications'),
  unread: () => request<{ count: number }>('/api/notifications/unread'),
  markNotificationRead: (id: string) => request<unknown>(`/api/notifications/${id}/read`, 'POST'),
  creditSelf: () => request<CreditSelf>('/api/credit/self'),
  creditRanking: (campusId = 1, limit = 20) =>
    request<CreditRankEntry[]>(`/api/credit/ranking?campusId=${campusId}&limit=${limit}`),
};

/** 金额展示：分 -> 元 */
export function yuan(cents: number): string {
  return `¥${(cents / 100).toFixed(2)}`;
}

export const STATUS_TEXT: Record<string, string> = {
  DRAFT: '草稿', PUBLISHED: '待抢单', LOCKED: '待确认', ACCEPTED: '待取货',
  PICKED_UP: '配送中', DELIVERED: '待确认完成', SETTLED: '已结算',
  CLOSED: '已关闭', CANCELLED: '已取消', DISPUTED: '争议中', REFUNDED: '已退款',
};

export const ACTION_TEXT: Record<string, string> = {
  GRAB: '抢单', CONFIRM: '确认接单', PICKUP: '取货', DELIVER: '送达',
  SETTLE: '确认完成（结算）', CANCEL: '取消并退款', DISPUTE: '发起争议',
  ARBITRATE: '仲裁',
};
