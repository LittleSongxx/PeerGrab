# 边缘加固：跑腿双桶限流与内部端点凭证

> 状态：已实施（P13）。本文说明入口侧的两类加固：**可信跑腿的共享预算**与**内部运维端点的第二凭证**。
> 对应代码：`RunnerRateLimiterPort` / `RedisRunnerRateLimiterAdapter` / `InternalAuthInterceptor`。

## 1. 为什么在抢单 v2 之外还需要跑腿预算

抢单 v2 解决的是“同一 requestId 只产生一个结果”，但它不限制**一个身份能发起多少次尝试**。
没有身份预算时，公开演示站上的单个账号可以：
- 对同一任务高频重放（每次同 requestId 都会走到持久请求登记）；
- 在多个任务之间快速轮询，放大 Redis/DB 压力；
- 在 202/网络失败后用查询端点无限轮询。

因此入口增加三个桶（全部在 Redis，跨 API 副本共享）：

| 桶 | 默认速率/容量 | 作用 |
|---|---|---|
| 跨任务桶 `:{runner}:all` | 2/s，burst 4 | 一个跑腿全局抢单尝试预算 |
| 单任务桶 `:{runner}:errand:{id}` | 0.5/s，burst 2 | 同一跑腿对同一任务的重复尝试 |
| 查询桶 `:{runner}:query` | 5/s，burst 10 | 结果查询独立预算，避免拖垮持久请求读路径 |

实现要点：
- **Lua 先检查所有桶再一起扣减**：不会出现“跨任务桶扣了、单任务桶拒绝”的半扣减；
- **Redis TIME + 时钟高水位**：NTP 回拨不会重复补充令牌；
- **Redis 不可用返回 503**，不把限流失效当“额度充足”；
- **查询另有本机恢复预算**（默认 10/s）：Redis 整体故障时仍允许少量查询把 202 请求收敛掉；
- 限流发生在**持久请求登记之前**，避免过热请求先写库再被拒绝。

接入点：`GrabRequestUseCase.grab/query`。v1 同步 `/grab` 保持原行为用于回滚，不在此预算内；
正式切换后应下线 v1，统一走 v2 入口。

## 2. 内部端点第二凭证

`/api/internal/**` 只对仲裁员开放，但仲裁员 token 泄露会让缓存统计/校验/预热门这些高消耗操作暴露。
新增可选的 `X-Internal-Token`：

| 配置 | 行为 |
|---|---|
| `peergrab.auth.internal-token` 为空 | 不启用第二道校验（本地/CI/历史压测客户端兼容） |
| 已配置且 ≥32 字节 | 请求必须携带相同 `X-Internal-Token`，否则 401 |
| 已配置但 <32 字节 | **应用启动直接失败**，防止弱口令 |

比较使用 `MessageDigest.isEqual`（常量时间），失败计入 `peergrab.internal.auth{outcome=rejected}`。
生产部署应同时保留 Nginx 对 `/api/internal/` 的 404 与这里配置的 token（纵深防御）。

## 3. 配置

| 键 | 默认 | 说明 |
|---|---|---|
| `peergrab.grab.runner-limit.rate` | `2` | 跨任务桶每秒补充令牌 |
| `peergrab.grab.runner-limit.burst` | `4` | 跨任务桶容量 |
| `peergrab.grab.runner-limit.errand-rate` | `0.5` | 单任务桶每秒补充令牌 |
| `peergrab.grab.runner-limit.errand-burst` | `2` | 单任务桶容量 |
| `peergrab.grab.runner-limit.query-rate` | `5` | 查询桶每秒补充令牌 |
| `peergrab.grab.runner-limit.query-burst` | `10` | 查询桶容量 |
| `peergrab.grab.runner-limit.recovery-query-per-second` | `10` | Redis 故障时本机查询恢复预算 |
| `peergrab.auth.internal-token` | 空 | 内部端点第二凭证（≥32 字节；空=不启用） |

## 4. 失败语义与客户端契约

- 429：`Retry-After`（毫秒）由桶计算得出；客户端应等待后**保留 requestId** 重试（v2 语义允许同 ID 重试；
  终态失败才需要换 ID）。
- 503：限流存储不可用；客户端保留 requestId 指数退避重试。
- 401（内部端点）：缺少/错误 `X-Internal-Token`；不暴露具体原因。
- 限流拒绝**不登记** `grab_request`，因此不会污染持久请求表。

## 5. 测试

- 单元：`InternalAuthInterceptorTest`（未配置/错误/正确/短 token 启动失败）、
  `GrabRequestUseCaseTest`（429/503/查询预算分支）。
- 集成：`RunnerRateLimitIT`（跨任务桶共享、单任务桶独立、查询桶独立、Retry-After > 0）。
- 全量 `mvn -B -ntp -Dpeergrab.it=true test` 在隔离 MySQL/Redis 上执行：**342 项、0 失败、0 错误、0 跳过，其中 88 项 IT 真实执行**（2026-10-28）。
- 真实栈：`verify_grab_v2_live.py` 8 步全过（发布/抢中/重放/查询/落败/429）；前端 `live_api_smoke.py` 11/11 通过。
- 调优记录：终态请求的重放优先于抢单预算（走查询桶），避免客户端响应丢失后因预算耗尽拿不到已确定结果；新尝试才消耗抢单桶。

## 6. 已知边界

1. 限流只覆盖 v2 抢单入口；v1 同步端点仍按原行为运行，切换完成后应删除 v1。
2. 限流维度是 `runnerId`（来自已认证 token），不覆盖来源 IP；IP/路由级预算应在网关/Nginx 层做。
3. 单实例部署下 Redis 故障会短暂把抢单主路径变成 503；客户端重试与 v2 持久请求保证不丢结果。
4. 未引入新的限流中间件（Sentinel 仍负责单实例热点任务），两个预算叠加而不是互相替代。
