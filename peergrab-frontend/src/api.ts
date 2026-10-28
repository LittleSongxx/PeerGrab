// 后端请求封装：token 管理 + Result 统一处理。
//
// 后端响应结构是 Result<T> = { code, message, data }：
//   code === 'OK' 表示成功；其他 code（SLOT_FULL / NOT_CURRENT_GRABBER 等）
//   是业务结果而不是服务器错误——前端按 code 分支处理，绝不解析文案。
//
// 401 处理要区分三种情况：真正过期（清会话）、旧请求回来时身份已经切换、
// 网络/5xx 导致的刷新失败（保留会话）。只有前两种才允许动本地登录态。

import type { Cents } from "./utils/money";

export interface ApiResult<T> {
  code: string;
  message: string;
  data: T;
}

/** 登录响应里的服务端角色：仲裁员与普通用户；未知/旧值一律按 null 处理 */
export type UserRole = "ARBITRATOR" | "USER";

export class BizError extends Error {
  code: string;
  constructor(code: string, message: string) {
    super(message);
    this.code = code;
  }
}

const TOKEN_KEY = "peergrab_token";
const REFRESH_KEY = "peergrab_refresh";
const USER_KEY = "peergrab_user";
const ROLE_KEY = "peergrab_role";
const AUTH_NOTICE_KEY = "peergrab_auth_notice";
const REQUEST_TIMEOUT_MS = 15000;

function normalizeRole(value: unknown): UserRole | null {
  return value === "ARBITRATOR" || value === "USER" ? value : null;
}

// localStorage / sessionStorage 在隐私模式、配额满、被手工破坏时都可能抛异常，
// 登录态读写必须包一层，不能让它把整个 React 挂载打挂。
function readStorage(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function writeStorage(key: string, value: string): void {
  try {
    localStorage.setItem(key, value);
  } catch {
    // 存储不可用时仍允许本次会话在内存中工作。
  }
}

function removeStorage(key: string): void {
  try {
    localStorage.removeItem(key);
  } catch {
    // ignore
  }
}

function readStorageJson<T>(key: string, fallback: T): T {
  const raw = readStorage(key);
  if (!raw) return fallback;
  try {
    return JSON.parse(raw) as T;
  } catch {
    removeStorage(key);
    return fallback;
  }
}

function setAuthNotice(message: string): void {
  try {
    sessionStorage.setItem(AUTH_NOTICE_KEY, message);
  } catch {
    // ignore
  }
}

export function consumeAuthNotice(): string | null {
  try {
    const message = sessionStorage.getItem(AUTH_NOTICE_KEY);
    if (message) sessionStorage.removeItem(AUTH_NOTICE_KEY);
    return message;
  } catch {
    return null;
  }
}

let token: string | null = readStorage(TOKEN_KEY);
let currentUserId: number | null = readStorageJson<number | null>(USER_KEY, null);
let refreshToken: string | null = readStorage(REFRESH_KEY);
let role: UserRole | null = normalizeRole(readStorage(ROLE_KEY));
let refreshInFlight: Promise<RefreshOutcome> | null = null;
let authGeneration = 0;

/** 跨标签同步：另一个标签登录/刷新/登出后，本标签内存跟随 localStorage。 */
function syncFromStorage(): boolean {
  const nextToken = readStorage(TOKEN_KEY);
  const nextRefresh = readStorage(REFRESH_KEY);
  const nextUser = readStorageJson<number | null>(USER_KEY, null);
  const nextRole = normalizeRole(readStorage(ROLE_KEY));
  const changed =
    nextToken !== token || nextRefresh !== refreshToken || nextUser !== currentUserId || nextRole !== role;
  token = nextToken;
  refreshToken = nextRefresh;
  currentUserId = nextUser;
  role = nextRole;
  return changed;
}

if (typeof window !== "undefined") {
  window.addEventListener("storage", (event) => {
    if (!event.key || !event.key.startsWith("peergrab_") || event.key === AUTH_NOTICE_KEY) return;
    if (syncFromStorage()) {
      authGeneration += 1;
      window.dispatchEvent(new Event("peergrab:auth-changed"));
    }
  });
}

export interface DemoCredentials {
  token: string;
  refreshToken?: string;
  userId: number;
  role?: UserRole;
}

type RefreshOutcome = "refreshed" | "unauthorized" | "transient";

export function getToken() {
  return token;
}

export function getUserId() {
  return currentUserId;
}

/** 当前登录角色；未登录或后端未返回时为 null（按普通用户处理） */
export function getRole(): UserRole | null {
  return role;
}

function requestSignal(): AbortSignal | undefined {
  try {
    return AbortSignal.timeout(REQUEST_TIMEOUT_MS);
  } catch {
    return undefined;
  }
}

async function fetchWithTimeout(input: string, init: RequestInit): Promise<Response> {
  try {
    return await fetch(input, { ...init, signal: init.signal ?? requestSignal() });
  } catch (error) {
    // 组件卸载/超时/断网都归为可重试网络错误；保留 requestId 的业务侧会据此复用。
    if (error instanceof DOMException && error.name === "AbortError") {
      throw new BizError("NETWORK_ERROR", "请求超时，请稍后重试");
    }
    throw new BizError("NETWORK_ERROR", "网络连接失败，请稍后重试");
  }
}

async function parseResponse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    // 网关超时/502 等通常返回 HTML，直接 .json() 会变成难以排查的 SyntaxError。
    throw new BizError("HTTP_ERROR", `服务暂时不可用（HTTP ${response.status}），请稍后重试`);
  }
  let result: ApiResult<T>;
  try {
    result = (await response.json()) as ApiResult<T>;
  } catch {
    throw new BizError("BAD_RESPONSE", "服务响应格式异常，请稍后重试");
  }
  if (result.code !== "OK") throw new BizError(result.code, result.message);
  return result.data;
}

/** Request a demo token without changing the current browser identity. */
export async function issueDemoCredentials(userId: number, password: string): Promise<DemoCredentials> {
  const response = await fetchWithTimeout("/api/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ userId, password }),
  });
  return parseResponse<DemoCredentials>(response);
}

export async function login(userId: number, password: string): Promise<string> {
  const credentials = await issueDemoCredentials(userId, password);
  authGeneration++;
  token = credentials.token;
  refreshToken = credentials.refreshToken ?? null;
  currentUserId = userId;
  role = normalizeRole(credentials.role);
  writeStorage(TOKEN_KEY, credentials.token);
  writeStorage(USER_KEY, JSON.stringify(userId));
  if (refreshToken) writeStorage(REFRESH_KEY, refreshToken);
  else removeStorage(REFRESH_KEY);
  if (role) writeStorage(ROLE_KEY, role);
  else removeStorage(ROLE_KEY);
  window.dispatchEvent(new Event("peergrab:auth-changed"));
  return credentials.token;
}

function clearLocalAuth(notice?: string) {
  authGeneration++;
  token = null;
  refreshToken = null;
  currentUserId = null;
  role = null;
  removeStorage(TOKEN_KEY);
  removeStorage(REFRESH_KEY);
  removeStorage(USER_KEY);
  removeStorage(ROLE_KEY);
  if (notice) setAuthNotice(notice);
  window.dispatchEvent(new Event("peergrab:auth-changed"));
}

async function renewSession(): Promise<RefreshOutcome> {
  // 别的标签页可能刚刷新过：先同步，避免拿轮换前的旧 refreshToken 去兑换。
  if (syncFromStorage() && token) return "refreshed";
  if (!refreshToken) return "unauthorized";
  if (refreshInFlight) return refreshInFlight;

  const oldRefreshToken = refreshToken;
  const generation = authGeneration;
  refreshInFlight = (async (): Promise<RefreshOutcome> => {
    let response: Response;
    try {
      response = await fetchWithTimeout("/api/auth/refresh", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ refreshToken: oldRefreshToken }),
      });
    } catch {
      return "transient";
    }
    if (response.status === 400 || response.status === 401 || response.status === 403) return "unauthorized";
    if (!response.ok) return "transient";

    let body: ApiResult<{ accessToken: string; refreshToken: string }>;
    try {
      body = (await response.json()) as ApiResult<{ accessToken: string; refreshToken: string }>;
    } catch {
      return "transient";
    }
    if (
      body.code === "UNAUTHORIZED" ||
      body.code === "INVALID_REFRESH_TOKEN" ||
      body.code === "REFRESH_TOKEN_REUSED"
    ) {
      return "unauthorized";
    }
    if (body.code !== "OK" || !body.data?.accessToken || !body.data?.refreshToken) return "transient";
    if (generation !== authGeneration) {
      // 刷新期间身份已切换：把这次刷新出来的令牌作废，避免串号。
      void revokeCredentials({ token: body.data.accessToken, refreshToken: body.data.refreshToken });
      return "transient";
    }
    token = body.data.accessToken;
    refreshToken = body.data.refreshToken;
    writeStorage(TOKEN_KEY, token);
    writeStorage(REFRESH_KEY, refreshToken);
    // WS 等长连接据此重连；TopBar 等订阅方也可据此恢复。
    window.dispatchEvent(new Event("peergrab:auth-refreshed"));
    return "refreshed";
  })().finally(() => {
    refreshInFlight = null;
  });
  return refreshInFlight;
}

/** 供 WebSocket 等非 request 链路复用：仅在存在 refresh token 时尝试刷新 */
export async function refreshSession(): Promise<boolean> {
  return (await renewSession()) === "refreshed";
}

/** Invalidate both server credentials, then clear local state even when offline. */
export async function revokeCredentials(credentials: Pick<DemoCredentials, "token" | "refreshToken">): Promise<void> {
  try {
    await fetch("/api/auth/logout", {
      method: "POST",
      headers: { Authorization: `Bearer ${credentials.token}`, "Content-Type": "application/json" },
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

/**
 * 带鉴权发请求并完成 401 刷新/重放，返回原始 Response（不解析 body）。
 * 普通接口 request 与 v2 抢单的原始响应共用这一段，保证登录态处理完全一致。
 */
async function sendAuthorized(
  path: string,
  method = "GET",
  body?: unknown,
  extraHeaders?: Record<string, string>,
): Promise<Response> {
  const send = (activeToken: string | null) =>
    fetchWithTimeout(path, {
      method,
      headers: {
        ...(activeToken ? { Authorization: `Bearer ${activeToken}` } : {}),
        ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
        ...extraHeaders,
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });

  const requestGeneration = authGeneration;
  const requestToken = token;
  let resp = await send(requestToken);
  let activeToken = requestToken;

  if (resp.status === 401) {
    // 请求期间别的标签/请求已经换了新令牌：直接重放，不能清掉新会话。
    if (syncFromStorage() && token && token !== requestToken) {
      activeToken = token;
      resp = await send(activeToken);
    } else {
      const outcome = await renewSession();
      if (outcome === "refreshed") {
        activeToken = token;
        resp = await send(activeToken);
      } else if (outcome === "transient" && syncFromStorage() && token && token !== requestToken) {
        activeToken = token;
        resp = await send(activeToken);
      } else if (outcome === "unauthorized") {
        if (requestGeneration === authGeneration) {
          clearLocalAuth("登录状态已过期，请重新登录");
          throw new BizError("UNAUTHORIZED", "登录状态已过期，请重新登录");
        }
        throw new BizError("UNAUTHORIZED", "会话已切换，请重试");
      } else {
        throw new BizError("NETWORK_ERROR", "网络异常，登录状态已保留，请稍后重试");
      }
    }
  }

  if (resp.status === 401) {
    if (requestGeneration === authGeneration && activeToken === requestToken) {
      clearLocalAuth("登录状态已过期，请重新登录");
      throw new BizError("UNAUTHORIZED", "登录状态已过期，请重新登录");
    }
    // 旧会话的 401 晚到：保持当前登录状态，只让这一条请求失败。
    throw new BizError("UNAUTHORIZED", "会话已切换，请重试");
  }

  return resp;
}

/** 发请求并返回 data；code 非 OK 时抛 BizError（带业务码） */
async function request<T>(
  path: string,
  method = "GET",
  body?: unknown,
  extraHeaders?: Record<string, string>,
): Promise<T> {
  const resp = await sendAuthorized(path, method, body, extraHeaders);
  return parseResponse<T>(resp);
}

// ── 抢单 v2（持久幂等请求）─────────────────────────────
//
// 这些端点用 HTTP 409/429/503 表达确定业务结果，不能套用「非 2xx 即 HTTP_ERROR」，
// 否则前端只能拿到一层笼统的「服务不可用」，丢掉了 SLOT_FULL / STALE_VERSION 等语义。
// 低层只负责把 HTTP 状态 + Result body 原样带回来，分支判断在 grabRequests.ts。

/** 与后端 GrabRequestUseCase.Result 对齐的 data 结构。 */
export interface GrabRequestData {
  requestId: string;
  /** PROCESSING / SUCCEEDED / REJECTED / EXPIRED；登记前失败时为 null */
  status: string | null;
  code: string;
  grabbed: boolean;
  appliedRound: number | null;
  replayed: boolean;
  retryAfterMillis: number;
  httpStatus: number;
}

/** 抢单接口的原始响应：HTTP 状态码 + 解析出的 Result body（非 JSON 时为 null）。 */
export interface GrabHttpResult {
  httpStatus: number;
  result: ApiResult<GrabRequestData> | null;
}

function decodeGrabResponse(response: Response): Promise<GrabHttpResult> {
  return response
    .json()
    .then((body: unknown) => ({ httpStatus: response.status, result: body as ApiResult<GrabRequestData> }))
    .catch(() => ({ httpStatus: response.status, result: null }));
}

/** 已登录会话的抢单 POST；requestId 由 grabRequests.ts 生成并持久化 */
export async function grabV2(
  errandId: string,
  expectedRound: number,
  requestId: string,
): Promise<GrabHttpResult> {
  const response = await sendAuthorized(
    `/api/errands/${errandId}/grab-v2`,
    "POST",
    { expectedRound },
    { "X-Request-Id": requestId },
  );
  return decodeGrabResponse(response);
}

/** 查询同一 requestId 的持久结果：202/网络失败后的恢复入口 */
export async function grabRequest(errandId: string, requestId: string): Promise<GrabHttpResult> {
  const response = await sendAuthorized(`/api/errands/${errandId}/grab-requests/${encodeURIComponent(requestId)}`);
  return decodeGrabResponse(response);
}

/**
 * 用显式 token 发并发抢单 POST。
 *
 * 任务广场的「双人同时抢单」演示要在同一页面同时以两个演示身份发请求，
 * 不能复用模块级的当前登录 token，因此这里显式传入凭据；401 刷新不适用
 * （演示凭据刚签发，且每个请求独立）。
 */
export async function grabV2WithToken(
  accessToken: string,
  errandId: string,
  expectedRound: number,
  requestId: string,
): Promise<GrabHttpResult> {
  const response = await fetchWithTimeout(`/api/errands/${errandId}/grab-v2`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/json",
      "X-Request-Id": requestId,
    },
    body: JSON.stringify({ expectedRound }),
  });
  return decodeGrabResponse(response);
}

// ── 任务 ──────────────────────────────────────────────
export interface ErrandCard {
  /** 后端以字符串返回：雪花 ID 超过 JS 安全整数（2^53），
   *  按 number 解析会截断精度，全程必须按 string 处理 */
  id: string;
  title: string;
  status: string;
  type: string;
  /** 后端把 long 统一序列化为字符串，金额字段在读侧按 Cents 处理 */
  rewardCents: Cents;
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
  time: string;
  from: string;
  to: string;
  round: number;
  operatorId: string;
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

/** 钱包流水：accountType 区分个人钱包与平台账户（USER / ESCROW / COMMISSION） */
export interface LedgerEntry {
  id: string;
  time: string;
  direction: string;
  amountCents: Cents;
  refType: string;
  refId: string;
  bizNo: string;
  accountType: string;
}

export const api = {
  listByCursor: (campusId = 1, cursor = "", size = 20, status?: string) => {
    const params = new URLSearchParams({ campusId: String(campusId), cursor, size: String(size) });
    if (status) params.set("status", status);
    return request<ErrandCursorPage>(`/api/errands?${params.toString()}`);
  },
  mine: (role: "PUBLISHED_BY_ME" | "GRABBED_BY_ME", page = 0, size = 20) =>
    request<ErrandCard[]>(`/api/errands/mine?role=${role}&page=${page}&size=${size}`),
  detail: (id: string) => request<ErrandCard>(`/api/errands/${id}`),
  timeline: (id: string) => request<StatusChange[]>(`/api/errands/${id}/timeline`),
  publish: (req: { title: string; rewardCents: number; slotTotal: number; type?: string }, requestId: string) =>
    request<{ errandId: string; status: string }>("/api/errands", "POST", req, {
      "X-Request-Id": requestId,
    }),
  // 抢单 v2：低层返回 HTTP 状态 + Result body，业务分支集中在 grabRequests.ts
  grabV2,
  grabRequest,
  // settle/cancel 成功时 data 是 { result: 'SETTLED' | 'ALREADY_SETTLED' | ... }
  action: (id: string, name: "confirm" | "pickup" | "deliver" | "settle" | "cancel" | "dispute") =>
    request<{ result?: string } | null>(`/api/errands/${id}/${name}`, "POST"),
  arbitrate: (id: string, favor: "RUNNER" | "PUBLISHER") =>
    request<{ result: string }>(`/api/errands/${id}/arbitrate`, "POST", { favor }),
  wallet: () => request<{ availableCents: Cents; frozenCents: Cents }>("/api/wallet"),
  ledgerByCursor: (cursor = "", size = 20) => {
    const params = new URLSearchParams({ cursor, size: String(size) });
    return request<{ items: LedgerEntry[]; nextCursor: string }>(`/api/wallet/ledger?${params.toString()}`);
  },
  notifications: (page = 0, size = 20) =>
    request<{
      id: string;
      errandId: string;
      type: string;
      content: string;
      time: string;
      read: boolean;
    }[]>(`/api/notifications?page=${page}&size=${size}`),
  unread: () => request<{ count: number }>("/api/notifications/unread"),
  markNotificationRead: (id: string) => request<unknown>(`/api/notifications/${id}/read`, "POST"),
  creditSelf: () => request<CreditSelf>("/api/credit/self"),
  creditRanking: (campusId = 1, limit = 20) =>
    request<CreditRankEntry[]>(`/api/credit/ranking?campusId=${campusId}&limit=${limit}`),
};

export const STATUS_TEXT: Record<string, string> = {
  DRAFT: "草稿",
  PUBLISHED: "待抢单",
  LOCKED: "待确认",
  ACCEPTED: "待取货",
  PICKED_UP: "配送中",
  DELIVERED: "待确认完成",
  SETTLED: "已结算",
  CANCELLED: "已取消",
  DISPUTED: "争议中",
  REFUNDED: "已退款",
};

export const ACTION_TEXT: Record<string, string> = {
  GRAB: "抢单",
  CONFIRM: "确认接单",
  PICKUP: "取货",
  DELIVER: "送达",
  SETTLE: "确认完成（结算）",
  CANCEL: "取消并退款",
  DISPUTE: "发起争议",
  ARBITRATE: "仲裁",
};
