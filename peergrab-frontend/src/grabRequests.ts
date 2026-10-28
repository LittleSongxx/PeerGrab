// v2 抢单请求客户端（持久幂等）。
//
// 后端契约见 docs/architecture/grab-request-idempotency.md：
// - POST /api/errands/{id}/grab-v2（Header X-Request-Id 必填 UUID，Body {expectedRound}）
//   200=已确定结果，202=处理中，409/429/503 等=确定失败/冲突/限流/依赖不可用；
// - GET  /api/errands/{id}/grab-requests/{requestId} 是 202/网络失败后的恢复入口；
// - 同一 requestId 只会收敛到一个结果，重放时 replayed=true，不会二次占名额。
//
// 客户端的三条纪律：
// 1) 请求键先落 sessionStorage（不可用时退化为内存）再发请求：网络在「服务端已处理、
//    响应没回来」时断开，只有这个键能把后续查询/重放接上；
// 2) 结果未知（202 / 网络错误 / 网关返回非 Result 的 5xx）时用同一 ID 轮询 GET，
//    间隔 300ms、上限 30s；能判定终态才清理持久键；
// 3) 带 Result body 的 4xx/5xx 是服务端确定答案：按契约换新 requestId，即清理旧键后
//    由用户重新发起，绝不复用旧 ID（429/503 在本分支同样被持久化为确定失败）。

import { api, BizError, getUserId } from "./api";
import type { GrabHttpResult } from "./api";

const STORAGE_PREFIX = "peergrab_grab_request";

/** 恢复轮询间隔：后端 202 通常很快收敛，300ms 兼顾时延与请求量。 */
export const GRAB_POLL_INTERVAL_MS = 300;
/** 单次抢单恢复查询的总预算；超时仍未终态则保留请求键，等下一次恢复。 */
export const GRAB_POLL_TIMEOUT_MS = 30_000;

interface PendingGrab {
  requestId: string;
  round: number;
}

/** 抢单尝试的结构化结果：UI 只按 kind/code 分支，不解析后端文案。 */
export type GrabOutcome =
  | { kind: "success"; requestId: string; replayed: boolean; appliedRound: number | null }
  | { kind: "processing"; requestId: string; message: string }
  | { kind: "failure"; requestId: string; code: string; message: string; retryable: boolean };

/** 把一次 GrabHttpResult 读成「成功 / 处理中 / 确定失败」；读不出结论返回 null（非 Result 网关响应）。 */
type GrabReading =
  | { kind: "success"; replayed: boolean; appliedRound: number | null }
  | { kind: "processing" }
  | { kind: "failure"; code: string; message: string; retryable: boolean };

/** 契约里「重新发起一次可能成功」的确定失败；文案上给用户可重试提示。 */
const RETRYABLE_CODES = new Set([
  "GRAB_RATE_LIMITED",
  "DEPENDENCY_UNAVAILABLE",
  "REQUEST_CONFLICT",
  "REQUEST_EXPIRED",
  "GRAB_CONFLICT",
]);

/** 稳定业务码的兜底文案：后端 message 可用时优先用后端文案。 */
const CODE_MESSAGE: Record<string, string> = {
  SLOT_FULL: "名额已满，任务已被其他跑腿抢中",
  ALREADY_GRABBED: "你已抢过该任务",
  STALE_VERSION: "任务状态已被更新，请刷新后重试",
  REQUEST_CONFLICT: "请求键与已有请求内容不匹配，请重新发起",
  REQUEST_NOT_FOUND: "抢单请求不存在",
  REQUEST_EXPIRED: "本次抢单请求已过期，请重新发起",
  GRAB_RATE_LIMITED: "当前抢单请求过多，请稍后再试",
  DEPENDENCY_UNAVAILABLE: "服务暂时繁忙，请稍后重试",
  ERRAND_NOT_FOUND: "任务不存在或已被删除",
  ERRAND_NOT_GRABBABLE: "任务当前不可抢",
  SELF_GRAB_FORBIDDEN: "不能抢自己发布的任务",
  ROLE_NOT_ALLOWED: "当前身份不能参与抢单",
  CREDIT_TOO_LOW: "信用分不足，暂时不能抢单",
  INVALID_ARGUMENT: "请求参数无效，请刷新后重试",
  GRAB_CONFLICT: "并发冲突，抢单失败，请重试",
  UNAUTHORIZED: "登录状态已过期，请重新登录",
};

// 内存兜底：隐私模式 / 存储配额满时 sessionStorage 会抛异常，
// 至少保证当前页面会话内的重试仍然复用同一个请求键。
const memoryPending = new Map<string, PendingGrab>();

function pendingKey(errandId: string): string | null {
  const userId = getUserId();
  if (userId === null || userId === undefined) return null;
  // 键里带用户 id：换号后不会读到上一个人的请求，避免用别人的 requestId 查询/重放。
  return `${STORAGE_PREFIX}:${userId}:${errandId}`;
}

function readPending(errandId: string): PendingGrab | null {
  const key = pendingKey(errandId);
  if (!key) return null;
  const cached = memoryPending.get(key);
  if (cached) return cached;
  try {
    const raw = sessionStorage.getItem(key);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<PendingGrab>;
    if (typeof parsed.requestId !== "string" || typeof parsed.round !== "number") {
      sessionStorage.removeItem(key);
      return null;
    }
    const pending: PendingGrab = { requestId: parsed.requestId, round: parsed.round };
    memoryPending.set(key, pending);
    return pending;
  } catch {
    return null;
  }
}

function writePending(errandId: string, pending: PendingGrab): void {
  const key = pendingKey(errandId);
  if (!key) return;
  memoryPending.set(key, pending);
  try {
    sessionStorage.setItem(key, JSON.stringify(pending));
  } catch {
    // 存储不可用时仍能靠内存里的键完成本页面会话内的恢复。
  }
}

function clearPending(errandId: string): void {
  const key = pendingKey(errandId);
  if (!key) return;
  memoryPending.delete(key);
  try {
    sessionStorage.removeItem(key);
  } catch {
    // ignore
  }
}

/**
 * 生成后端要求的 UUID（GrabRequestUseCase 用 UUID.fromString 严格校验）。
 *
 * 非安全上下文（http:// 内网 IP 直连）没有 crypto.randomUUID，此时用时间戳 +
 * Math.random 拼一个 RFC 4122 v4 形状的串；不能退回 `req-xxx` 这种自定义格式，
 * 否则后端会直接判 INVALID_ARGUMENT。
 */
export function newGrabRequestId(): string {
  try {
    if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
      return crypto.randomUUID();
    }
  } catch {
    // 继续走降级实现
  }
  const stamp = Date.now().toString(16).padStart(12, "0").slice(-12);
  let random = "";
  for (let index = 0; index < 20; index += 1) {
    random += Math.floor(Math.random() * 16).toString(16);
  }
  const chars = (stamp + random).slice(0, 32).split("");
  chars[12] = "4"; // version 4
  chars[16] = ((parseInt(chars[16], 16) & 0x3) | 0x8).toString(16); // RFC 4122 variant
  const raw = chars.join("");
  return `${raw.slice(0, 8)}-${raw.slice(8, 12)}-${raw.slice(12, 16)}-${raw.slice(16, 20)}-${raw.slice(20)}`;
}

/**
 * 取本次抢单要用的 requestId：同任务同轮次已有未终结请求时复用（服务端重放收敛），
 * 轮次变化则换新键——requestId 的 payload hash 绑定 (errandId, round)，
 * 复用只会拿到 REQUEST_CONFLICT。
 */
function resolveRequestId(errandId: string, expectedRound: number): string {
  const pending = readPending(errandId);
  if (pending && pending.round === expectedRound) return pending.requestId;
  const requestId = newGrabRequestId();
  writePending(errandId, { requestId, round: expectedRound });
  return requestId;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => {
    window.setTimeout(resolve, ms);
  });
}

function isUnauthorized(error: unknown): boolean {
  return error instanceof BizError && error.code === "UNAUTHORIZED";
}

/** 解析一次抢单响应；返回 null 表示 body 不是 Result（网关 HTML 5xx 等），结果仍未知。 */
function readGrabResult(http: GrabHttpResult): GrabReading | null {
  const result = http.result;
  if (!result || typeof result.code !== "string") return null;
  const data = typeof result.data === "object" && result.data !== null ? result.data : null;
  const status = typeof data?.status === "string" ? data.status : null;
  if (result.code === "OK") {
    if (status === "SUCCEEDED" || data?.grabbed === true) {
      return {
        kind: "success",
        replayed: data?.replayed === true,
        appliedRound: typeof data?.appliedRound === "number" ? data.appliedRound : null,
      };
    }
    // 200 但没有 grabbed 标记：按处理中继续查询，不臆断成功。
    return { kind: "processing" };
  }
  if (status === "PROCESSING" || result.code === "REQUEST_PROCESSING" || http.httpStatus === 202) {
    return { kind: "processing" };
  }
  const code = typeof data?.code === "string" && data.code !== "OK" ? data.code : result.code;
  const message = result.message || CODE_MESSAGE[code] || "抢单失败，请稍后重试";
  return {
    kind: "failure",
    code,
    message,
    retryable: RETRYABLE_CODES.has(code) || status === "EXPIRED",
  };
}

function successOutcome(requestId: string, reading: Extract<GrabReading, { kind: "success" }>): GrabOutcome {
  return {
    kind: "success",
    requestId,
    replayed: reading.replayed,
    appliedRound: reading.appliedRound,
  };
}

function failureOutcome(requestId: string, reading: Extract<GrabReading, { kind: "failure" }>): GrabOutcome {
  return {
    kind: "failure",
    requestId,
    code: reading.code,
    message: reading.message,
    retryable: reading.retryable,
  };
}

function processingOutcome(requestId: string): GrabOutcome {
  return {
    kind: "processing",
    requestId,
    message: "抢单结果暂未确认：请求已登记，请稍后刷新任务详情查看；再次点击抢单会用同一请求号恢复核对。",
  };
}

/** 用同一 requestId 发一次恢复用 POST；网络/鉴权失败按 null（结果未知）处理。 */
async function repostSameRequest(
  errandId: string,
  expectedRound: number,
  requestId: string,
): Promise<GrabReading | null> {
  try {
    return readGrabResult(await api.grabV2(errandId, expectedRound, requestId));
  } catch (error) {
    if (isUnauthorized(error)) throw error;
    return null;
  }
}

/**
 * 用同一 requestId 轮询查询，直到终态或 30s 预算耗尽。
 *
 * allowRepost=true（POST 后恢复）时，若查询返回 REQUEST_NOT_FOUND——说明 POST 可能在
 * 登记前就失败（网关/网络丢请求）——用同一 ID 补发一次，幂等语义不变；
 * allowRepost=false（刷新后恢复）时没有重复发起的授权，只清理陈旧键。
 *
 * @returns 终态结果；预算耗尽返回 processing；恢复模式下无登记行返回 null。
 */
async function pollWithRequestId(
  errandId: string,
  requestId: string,
  expectedRound: number,
  allowRepost: boolean,
): Promise<GrabOutcome | null> {
  const deadline = Date.now() + GRAB_POLL_TIMEOUT_MS;
  let reposted = false;
  for (;;) {
    let reading: GrabReading | null = null;
    try {
      reading = readGrabResult(await api.grabRequest(errandId, requestId));
    } catch (error) {
      if (isUnauthorized(error)) throw error;
      // 网络抖动：结果仍未知，继续在剩余预算内查询。
      reading = null;
    }

    if (reading?.kind === "success") {
      clearPending(errandId);
      return successOutcome(requestId, reading);
    }

    if (reading?.kind === "failure") {
      if (reading.code !== "REQUEST_NOT_FOUND") {
        // 409/429/503/过期等都是服务端确定答案：清理旧键，下一次由用户换新 ID 发起。
        clearPending(errandId);
        return failureOutcome(requestId, reading);
      }
      if (!allowRepost) {
        // 恢复查询时连登记行都没有：这是陈旧键（如服务端从未收到 POST），清掉即可。
        clearPending(errandId);
        return null;
      }
      if (!reposted) {
        reposted = true;
        const repostReading = await repostSameRequest(errandId, expectedRound, requestId);
        if (repostReading?.kind === "success") {
          clearPending(errandId);
          return successOutcome(requestId, repostReading);
        }
        if (repostReading?.kind === "failure") {
          clearPending(errandId);
          return failureOutcome(requestId, repostReading);
        }
        // 处理中/仍未知：回到轮询继续等终态。
      }
    }

    if (Date.now() >= deadline) {
      // 结果仍不确定：保留持久键，下一次同轮次点击/恢复查询会复用同一 ID。
      return processingOutcome(requestId);
    }
    await sleep(Math.min(GRAB_POLL_INTERVAL_MS, Math.max(deadline - Date.now(), 0)));
  }
}

/**
 * 发起一次 v2 抢单，返回结构化结果。
 *
 * 同一 (任务, 轮次) 若上次请求尚未终结，会自动复用持久化的 requestId（先落盘再发请求），
 * 因此网络中断/刷新后重试不会二次占名额。
 */
export async function grabErrand(errandId: string, expectedRound: number): Promise<GrabOutcome> {
  const requestId = resolveRequestId(errandId, expectedRound);
  let reading: GrabReading | null;
  try {
    reading = readGrabResult(await api.grabV2(errandId, expectedRound, requestId));
  } catch (error) {
    if (isUnauthorized(error)) throw error;
    // 网络错误/超时：服务端可能已经处理完，不能直接判失败 → 走同一 ID 的恢复查询。
    return (await pollWithRequestId(errandId, requestId, expectedRound, true)) ?? processingOutcome(requestId);
  }
  if (reading?.kind === "success") {
    clearPending(errandId);
    return successOutcome(requestId, reading);
  }
  if (reading?.kind === "failure") {
    clearPending(errandId);
    return failureOutcome(requestId, reading);
  }
  // 202 处理中 / 网关返回非 Result body：用同一 ID 轮询查询直到终态。
  return (await pollWithRequestId(errandId, requestId, expectedRound, true)) ?? processingOutcome(requestId);
}

/**
 * 页面刷新/重新进入详情页时的恢复入口：只有存在未终结请求键时才查询。
 *
 * @returns 终态结果；没有待恢复请求（或恢复模式下无登记行）返回 null；
 *          预算内仍未终态返回 processing（持久键保留，供下一次恢复）。
 */
export async function recoverPendingGrab(
  errandId: string,
  expectedRound: number,
): Promise<GrabOutcome | null> {
  const pending = readPending(errandId);
  if (!pending) return null;
  if (pending.round !== expectedRound) {
    // 任务已流转到新轮次：旧键对应的请求内容不再匹配，后端只会回 REQUEST_CONFLICT。
    clearPending(errandId);
    return null;
  }
  return pollWithRequestId(errandId, pending.requestId, expectedRound, false);
}
